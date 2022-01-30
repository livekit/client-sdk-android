package io.livekit.android.sample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.livekit.android.sample.databinding.MainActivityBinding


class MainActivity : AppCompatActivity() {

    val viewModel by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = MainActivityBinding.inflate(layoutInflater)

        val urlString = viewModel.getSavedUrl()
        val tokenString = viewModel.getSavedToken()
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

                viewModel.setSavedUrl(url.editText?.text?.toString() ?: "")
                viewModel.setSavedToken(token.editText?.text?.toString() ?: "")

                Toast.makeText(
                    this@MainActivity,
                    "Values saved.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            resetButton.setOnClickListener {
                viewModel.reset()
                url.editText?.text = SpannableStringBuilder(MainViewModel.URL)
                token.editText?.text = SpannableStringBuilder(MainViewModel.TOKEN)

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
}