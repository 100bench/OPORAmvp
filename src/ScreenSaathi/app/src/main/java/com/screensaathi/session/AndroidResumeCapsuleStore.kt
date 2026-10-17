package com.screensaathi.session

import android.content.Context
import android.os.SystemClock
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** AndroidKeyStore AES/GCM plus private SharedPreferences ciphertext storage. */
class AndroidResumeCapsuleStore(context: Context) : ResumeCapsuleStore {
    private val delegate = DefaultResumeCapsuleStore(
        values = SharedPreferencesResumeValues(context.applicationContext),
        cipher = AndroidKeyStoreResumeCipher(),
        wallClock = System::currentTimeMillis,
        elapsedClock = SystemClock::elapsedRealtime,
    )

    override fun save(session: ScenarioSession) = delegate.save(session)

    override fun load(): ResumeCapsule? = delegate.load()

    override fun delete() = delegate.delete()
}

private class SharedPreferencesResumeValues(context: Context) : ResumeCapsuleValues {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(CAPSULE, null)

    override fun write(value: String) {
        check(preferences.edit().putString(CAPSULE, value).commit())
    }

    override fun delete() {
        check(preferences.edit().remove(CAPSULE).commit())
    }

    companion object {
        private const val PREFERENCES = "opora_resume"
        private const val CAPSULE = "encrypted_capsule"
    }
}

private class AndroidKeyStoreResumeCipher : ResumeCapsuleCipher {
    private val lock = Any()

    override fun seal(plaintext: ByteArray): SealedResumeCapsule? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        SealedResumeCapsule(
            iv = cipher.iv.copyOf(),
            ciphertext = cipher.doFinal(plaintext),
        )
    }.getOrNull()

    override fun open(sealed: SealedResumeCapsule): ByteArray? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_BITS, sealed.iv),
        )
        cipher.updateAAD(AAD)
        cipher.doFinal(sealed.ciphertext)
    }.getOrNull()

    private fun key(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance(KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
            }
            .generateKey()
    }

    companion object {
        private const val KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "opora_resume_capsule_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private val AAD = "opora-resume-capsule-v1".toByteArray(Charsets.UTF_8)
    }
}
