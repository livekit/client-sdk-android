package io.livekit.android.videoencodedecode

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.preference.PreferenceManager

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(application)

    fun getSavedUrl() = preferences.getString(PREFERENCES_KEY_URL, URL) as String
    fun getSavedToken1() = preferences.getString(PREFERENCES_KEY_TOKEN1, TOKEN1) as String
    fun getSavedToken2() = preferences.getString(PREFERENCES_KEY_TOKEN2, TOKEN2) as String

    fun setSavedUrl(url: String) {
        preferences.edit {
            putString(PREFERENCES_KEY_URL, url)
        }
    }

    fun setSavedToken1(token: String) {
        preferences.edit {
            putString(PREFERENCES_KEY_TOKEN1, token)
        }
    }

    fun setSavedToken2(token: String) {
        preferences.edit {
            putString(PREFERENCES_KEY_TOKEN2, token)
        }
    }

    fun reset() {
        preferences.edit { clear() }
    }

    companion object {
        private const val PREFERENCES_KEY_URL = "url"
        private const val PREFERENCES_KEY_TOKEN1 = "token1"
        private const val PREFERENCES_KEY_TOKEN2 = "token2"

        const val URL = ""
        const val TOKEN1 =
            ""
        const val TOKEN2 =
            ""
    }
}
