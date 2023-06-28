package io.livekit.android.sample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.livekit.android.sample.databinding.MainActivityBinding
import io.livekit.android.sample.util.requestNeededPermissions


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
                            token.editText?.text.toString(),
                            e2eeEnabled.isChecked,
                            e2eeKey.editText?.text.toString()
                        )
                    )
                }

                startActivity(intent)
            }

            saveButton.setOnClickListener {

                viewModel.setSavedUrl(url.editText?.text?.toString() ?: "")
                viewModel.setSavedToken(token.editText?.text?.toString() ?: "")
                viewModel.setSavedE2EEOn(e2eeEnabled.isChecked)
                viewModel.setSavedE2EEKey(e2eeKey.editText?.text?.toString() ?: "")

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
                e2eeEnabled.isChecked = false
                e2eeKey.editText?.text = SpannableStringBuilder("")

                Toast.makeText(
                    this@MainActivity,
                    "Values reset.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        setContentView(binding.root)

        requestNeededPermissions()
    }
}