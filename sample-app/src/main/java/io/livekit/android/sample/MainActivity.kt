/*
 * Copyright 2024-2026 LiveKit, Inc.
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

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import io.livekit.android.sample.databinding.MainActivityBinding
import io.livekit.android.sample.model.StressTest
import io.livekit.android.sample.model.TokenSourceArgs
import io.livekit.android.sample.util.requestNeededPermissions

class MainActivity : AppCompatActivity() {

    private val viewModel by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = MainActivityBinding.inflate(layoutInflater)

        val urlString = viewModel.getSavedUrl()
        val tokenString = viewModel.getSavedToken()
        val tokenServerIdString = viewModel.getSavedTokenServerId()
        val e2EEOn = viewModel.getE2EEOptionsOn()
        val e2EEKey = viewModel.getSavedE2EEKey()

        binding.run {
            url.editText?.text = SpannableStringBuilder(urlString)
            token.editText?.text = SpannableStringBuilder(tokenString)
            tokenServerId.editText?.text = SpannableStringBuilder(tokenServerIdString)
            e2eeEnabled.isChecked = e2EEOn
            e2eeKey.editText?.text = SpannableStringBuilder(e2EEKey)

            tokenModeTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    val literalMode = tab.position == TAB_POSITION_LITERAL
                    url.visibility = if (literalMode) View.VISIBLE else View.GONE
                    token.visibility = if (literalMode) View.VISIBLE else View.GONE
                    tokenServerId.visibility = if (literalMode) View.GONE else View.VISIBLE
                }

                override fun onTabUnselected(tab: TabLayout.Tab) {}

                override fun onTabReselected(tab: TabLayout.Tab) {}
            })

            connectButton.setOnClickListener {
                val tokenSourceArgs = if (tokenModeTabs.selectedTabPosition == TAB_POSITION_LITERAL) {
                    TokenSourceArgs.Literal(
                        url = url.editText?.text.toString(),
                        token = token.editText?.text.toString(),
                    )
                } else {
                    TokenSourceArgs.DevTokenServer(
                        tokenServerId = tokenServerId.editText?.text.toString(),
                    )
                }
                val intent = Intent(this@MainActivity, CallActivity::class.java).apply {
                    putExtra(
                        CallActivity.KEY_ARGS,
                        CallActivity.BundleArgs(
                            tokenSourceArgs = tokenSourceArgs,
                            e2eeOn = e2eeEnabled.isChecked,
                            e2eeKey = e2eeKey.editText?.text.toString(),
                            stressTest = StressTest.None,
                        ),
                    )
                }

                startActivity(intent)
            }

            saveButton.setOnClickListener {
                viewModel.setSavedUrl(url.editText?.text?.toString() ?: "")
                viewModel.setSavedToken(token.editText?.text?.toString() ?: "")
                viewModel.setSavedTokenServerId(tokenServerId.editText?.text?.toString() ?: "")
                viewModel.setSavedE2EEOn(e2eeEnabled.isChecked)
                viewModel.setSavedE2EEKey(e2eeKey.editText?.text?.toString() ?: "")

                Toast.makeText(
                    this@MainActivity,
                    "Values saved.",
                    Toast.LENGTH_SHORT,
                ).show()
            }

            resetButton.setOnClickListener {
                viewModel.reset()
                url.editText?.text = SpannableStringBuilder(MainViewModel.URL)
                token.editText?.text = SpannableStringBuilder(MainViewModel.TOKEN)
                tokenServerId.editText?.text = SpannableStringBuilder(MainViewModel.TOKEN_SERVER_ID)
                e2eeEnabled.isChecked = false
                e2eeKey.editText?.text = SpannableStringBuilder("")

                Toast.makeText(
                    this@MainActivity,
                    "Values reset.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        setContentView(binding.root)

        requestNeededPermissions()
    }

    companion object {
        private const val TAB_POSITION_LITERAL = 0
    }
}
