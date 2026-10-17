package com.screensaathi.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Lifecycle-owned observation of a validated default network. */
interface NetworkMonitor {
    /** Idempotently begin waiting for a future validated-network transition. */
    fun start(onValidatedNetwork: () -> Unit)

    /** Idempotently unregister and invalidate callbacks already in flight. */
    fun stop()
}

fun interface NetworkRegistration {
    fun unregister()
}

fun interface ValidatedNetworkRegistrar {
    fun register(onValidatedNetwork: () -> Unit): NetworkRegistration
}

/**
 * Pure lifecycle coordinator. Registration and unregistration are serialized
 * under one lock, so Stop/dispose cannot lose a registration still in flight.
 */
class LifecycleNetworkMonitor(
    private val registrar: ValidatedNetworkRegistrar,
) : NetworkMonitor {
    private val lock = Any()
    private var registration: NetworkRegistration? = null
    private var listener: (() -> Unit)? = null
    private var registrationEpoch = 0L

    override fun start(onValidatedNetwork: () -> Unit) {
        synchronized(lock) {
            if (registration != null) return
            val epoch = ++registrationEpoch
            listener = onValidatedNetwork

            val created = runCatching {
                registrar.register { deliver(epoch) }
            }.getOrNull()
            if (created == null) {
                listener = null
                registrationEpoch++
                return
            }
            registration = created
        }
    }

    override fun stop() {
        synchronized(lock) {
            registrationEpoch++
            listener = null
            val active = registration
            registration = null
            active?.let { runCatching(it::unregister) }
        }
    }

    private fun deliver(epoch: Long) {
        val current = synchronized(lock) {
            listener.takeIf { epoch == registrationEpoch }
        }
        current?.invoke()
    }
}

/** Android adapter. It reports connectivity only; it never invokes the planner. */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val delegate = LifecycleNetworkMonitor(AndroidValidatedNetworkRegistrar(context))

    override fun start(onValidatedNetwork: () -> Unit) = delegate.start(onValidatedNetwork)

    override fun stop() = delegate.stop()
}

private class AndroidValidatedNetworkRegistrar(context: Context) : ValidatedNetworkRegistrar {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun register(onValidatedNetwork: () -> Unit): NetworkRegistration {
        // A currently validated phone network is not evidence that the Mac VPN
        // or localhost proxy recovered. Require an unavailable -> validated
        // transition before automatic retry; the Retry button remains usable.
        val initiallyValidated = runCatching {
            val active = connectivityManager.activeNetwork ?: return@runCatching false
            connectivityManager.getNetworkCapabilities(active)?.isValidated() == true
        }.getOrDefault(false)

        val callback = object : ConnectivityManager.NetworkCallback() {
            private val callbackLock = Any()
            private var sawUnavailable = !initiallyValidated
            private var notified = false

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val delivery = synchronized(callbackLock) {
                    if (!capabilities.isValidated()) {
                        sawUnavailable = true
                        notified = false
                        null
                    } else if (sawUnavailable && !notified) {
                        notified = true
                        onValidatedNetwork
                    } else {
                        null
                    }
                }
                delivery?.invoke()
            }

            override fun onLost(network: Network) {
                synchronized(callbackLock) {
                    sawUnavailable = true
                    notified = false
                }
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)
        return NetworkRegistration {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }

    private fun NetworkCapabilities.isValidated(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
