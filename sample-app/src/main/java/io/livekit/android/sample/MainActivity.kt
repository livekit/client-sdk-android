package io.livekit.android.sample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import io.livekit.android.sample.databinding.MainActivityBinding


class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = MainActivityBinding.inflate(layoutInflater)

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val urlString = preferences.getString(PREFERENCES_KEY_URL, URL)
        val tokenString = preferences.getString(PREFERENCES_KEY_TOKEN, TOKEN)
        binding.run {
            url.editText?.text = SpannableStringBuilder(urlString)
            token.editText?.text = SpannableStringBuilder(tokenString)
            connectButton.setOnClickListener {
                val intent = Intent(this@MainActivity, CallActivity::class.java).apply {
                    putExtra(
                        CallActivity.KEY_ARGS,
                        CallActivity.BundleArgs(
                            url.editText?.text.toString(),
                            token.editText?.text.toString()
                        )
                    )
                }

                startActivity(intent)
            }

            saveButton.setOnClickListener {
                preferences.edit {
                    putString(PREFERENCES_KEY_URL, url.editText?.text.toString())
                    putString(PREFERENCES_KEY_TOKEN, token.editText?.text.toString())
                }

                Toast.makeText(
                    this@MainActivity,
                    "Values saved.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            resetButton.setOnClickListener {
                preferences.edit {
                    clear()
                }
                url.editText?.text = SpannableStringBuilder(URL)
                token.editText?.text = SpannableStringBuilder(TOKEN)

                Toast.makeText(
                    this@MainActivity,
                    "Values reset.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        setContentView(binding.root)

        requestPermissions()

    }

    private fun requestPermissions() {
        val requestPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                for (grant in grants.entries) {
                    if (!grant.value) {
                        Toast.makeText(
                            this,
                            "Missing permission: ${grant.key}",
                            Toast.LENGTH_SHORT
                        )
                            .show()
                    }
                }
            }
        val neededPermissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter {
                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) == PackageManager.PERMISSION_DENIED
            }
            .toTypedArray()
        if (neededPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(neededPermissions)
        }
    }

    companion object {
        const val PREFERENCES_KEY_URL = "url"
        const val PREFERENCES_KEY_TOKEN = "token"

        const val URL = "wss://livekit.watercooler.fm"
        const val TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE5ODQyMzE0OTgsImlzcyI6IkFQSU1teGlMOHJxdUt6dFpFb1pKVjlGYiIsImp0aSI6ImZvcnRoIiwibmJmIjoxNjI0MjMxNDk4LCJ2aWRlbyI6eyJyb29tIjoibXlyb29tIiwicm9vbUpvaW4iOnRydWV9fQ.PVx_lXAIGxcD2VRslosrbkigc777GXbu-DQME8hjJKI"
    }
}