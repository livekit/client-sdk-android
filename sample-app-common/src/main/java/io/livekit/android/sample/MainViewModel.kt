package io.livekit.android.sample

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.preference.PreferenceManager

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(application)

    fun getSavedUrl() = preferences.getString(PREFERENCES_KEY_URL, URL) as String
    fun getSavedToken() = preferences.getString(PREFERENCES_KEY_TOKEN, TOKEN) as String

    fun setSavedUrl(url: String) {
        preferences.edit {
            putString(PREFERENCES_KEY_URL, url)
        }
    }

    fun setSavedToken(token: String) {
        preferences.edit {
            putString(PREFERENCES_KEY_TOKEN, token)
        }
    }

    fun reset() {
        preferences.edit { clear() }
    }

    companion object {
        private const val PREFERENCES_KEY_URL = "url"
        private const val PREFERENCES_KEY_TOKEN = "token"

        const val URL = "ws://192.168.11.5:7880"
        const val TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE2NDUyNDU1ODEsImlzcyI6IkFQSVNRdmdrYWJZdXFUQSIsImp0aSI6InBob25lIiwibmJmIjoxNjQyNjUzNTgxLCJzdWIiOiJwaG9uZSIsInZpZGVvIjp7InJvb20iOiJteXJvb20iLCJyb29tSm9pbiI6dHJ1ZX19.JLJgQoCdPCTELJYsEhWGOiBuLl3eoZksWyAl08tJqLg" //nocommit
    }
}