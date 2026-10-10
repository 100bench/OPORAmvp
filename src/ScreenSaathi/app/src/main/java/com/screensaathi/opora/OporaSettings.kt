package com.screensaathi.opora

import android.content.Context

/**
 * The deliberately small local configuration for the MVP.
 *
 * There is no address-book sync: the optional call contact is entered by the
 * user and stays in private app storage. Guidance itself needs no configured
 * app — the current Accessibility window is the context.
 */
data class OporaSettings(
    val contactName: String = "",
    val contactPhone: String = "",
) {
    val hasContact: Boolean get() = contactName.isNotBlank() && contactPhone.isNotBlank()

    companion object {
        const val CONTACT_TARGET_ID = "favorite_contact"

        private const val PREFS = "opora_mvp"
        private const val CONTACT_NAME = "contact_name"
        private const val CONTACT_PHONE = "contact_phone"

        fun load(context: Context): OporaSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return OporaSettings(
                contactName = prefs.getString(CONTACT_NAME, "").orEmpty(),
                contactPhone = prefs.getString(CONTACT_PHONE, "").orEmpty(),
            )
        }

        fun save(context: Context, value: OporaSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CONTACT_NAME, value.contactName.trim())
                .putString(CONTACT_PHONE, normalizedPhone(value.contactPhone))
                .apply()
        }

        /** Returns an E.164-like dial string, or null for USSD/emergency input. */
        fun normalizedPhone(raw: String): String? {
            val compact = raw.filterNot { it.isWhitespace() || it in "()-" }
            if (!compact.matches(Regex("^\\+?[0-9]{7,15}$"))) return null
            val digits = compact.filter(Char::isDigit)
            if (digits in setOf("101", "102", "103", "104", "112", "911")) return null
            return compact
        }
    }
}
