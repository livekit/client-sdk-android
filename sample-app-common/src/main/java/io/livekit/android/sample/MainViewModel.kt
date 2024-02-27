/*
 * Copyright 2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.sample

import android.annotation.SuppressLint
import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import io.livekit.android.sample.common.BuildConfig
import io.livekit.android.util.LKLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(application)

    fun getSavedUrl() = preferences.getString(PREFERENCES_KEY_URL, URL) as String
    fun getSavedToken() = preferences.getString(PREFERENCES_KEY_TOKEN, TOKEN) as String
    fun getE2EEOptionsOn() = preferences.getBoolean(PREFERENCES_KEY_E2EE_ON, false)
    fun getSavedE2EEKey() = preferences.getString(PREFERENCES_KEY_E2EE_KEY, E2EE_KEY) as String

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

    fun setSavedE2EEOn(yesno: Boolean) {
        preferences.edit {
            putBoolean(PREFERENCES_KEY_E2EE_ON, yesno)
        }
    }

    fun setSavedE2EEKey(key: String) {
        preferences.edit {
            putString(PREFERENCES_KEY_E2EE_KEY, key)
        }
    }

    fun reset() {
        preferences.edit { clear() }
    }

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(2000)
                dumpReferenceTables()
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun dumpReferenceTables() {
        try {
            val cls = Class.forName("android.os.Debug")
            val method = cls.getDeclaredMethod("dumpReferenceTables")
            val con = cls.getDeclaredConstructor().apply {
                isAccessible = true
            }
            method.invoke(con.newInstance())
        } catch (e: Exception) {
            LKLog.e(e) { "Unable to dump reference tables, you can try `adb shell settings put global hidden_api_policy 1`" }
        }
    }
    companion object {
        private const val PREFERENCES_KEY_URL = "url"
        private const val PREFERENCES_KEY_TOKEN = "token"
        private const val PREFERENCES_KEY_E2EE_ON = "enable_e2ee"
        private const val PREFERENCES_KEY_E2EE_KEY = "e2ee_key"

        const val URL = BuildConfig.DEFAULT_URL
        const val TOKEN = BuildConfig.DEFAULT_TOKEN
        const val E2EE_KEY = "12345678"
    }
}
