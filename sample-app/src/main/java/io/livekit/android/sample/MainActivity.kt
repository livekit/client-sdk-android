package io.livekit.android.sample

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import androidx.appcompat.app.AppCompatActivity
import io.livekit.android.sample.databinding.MainActivityBinding

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = MainActivityBinding.inflate(layoutInflater)
        binding.run {
            url.editText?.text = URL
            token.editText?.text = TOKEN
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
        }

        setContentView(binding.root)
    }

    companion object {
        val URL = SpannableStringBuilder("192.168.11.2:7880")
        val TOKEN =
            SpannableStringBuilder("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE2MTg1NzgxNzMsImlzcyI6IkFQSXdMZWFoN2c0ZnVMWURZQUplYUtzU0UiLCJqdGkiOiJwaG9uZSIsIm5iZiI6MTYxNTk4NjE3MywidmlkZW8iOnsicm9vbSI6Im15cm9vbSIsInJvb21Kb2luIjp0cnVlfX0.O3UedhM9lwdPxsZJQoTfVk0qXc-0ukjV6oZCBIaRTck")
    }
}