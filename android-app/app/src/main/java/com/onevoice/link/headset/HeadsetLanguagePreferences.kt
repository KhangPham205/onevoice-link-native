package com.onevoice.link.headset

import android.content.Context

/** Small, local store for spoken/preferred endpoint-language assignments. */
internal class HeadsetLanguagePreferences(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun get(identityHash: String): HeadsetLanguage? =
        HeadsetLanguage.fromPersistedValue(
            preferences.getString(key(identityHash), null)
                ?: preferences.getString(legacyKey(identityHash), null),
        )

    fun set(identityHash: String, language: HeadsetLanguage?) {
        preferences.edit().apply {
            if (language == null) {
                remove(key(identityHash))
                remove(legacyKey(identityHash))
            } else {
                putString(key(identityHash), language.persistedValue)
                remove(legacyKey(identityHash))
            }
        }.apply()
    }

    private fun key(identityHash: String): String = "$KEY_PREFIX$identityHash"
    private fun legacyKey(identityHash: String): String = "$LEGACY_KEY_PREFIX$identityHash"

    private companion object {
        const val FILE_NAME = "headset_language_assignments_v1"
        const val KEY_PREFIX = "spoken_preferred_language."
        const val LEGACY_KEY_PREFIX = "receive_language."
    }
}
