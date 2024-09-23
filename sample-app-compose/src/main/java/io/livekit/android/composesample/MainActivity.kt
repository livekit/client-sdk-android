/*
 * Copyright 2023-2024 LiveKit, Inc.
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

package io.livekit.android.composesample

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.accompanist.pager.ExperimentalPagerApi
import io.livekit.android.composesample.ui.theme.AppTheme
import io.livekit.android.sample.MainViewModel
import io.livekit.android.sample.common.R
import io.livekit.android.sample.model.StressTest
import io.livekit.android.sample.util.requestNeededPermissions

@ExperimentalPagerApi
class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNeededPermissions()
        setContent {
            MainContent(
                defaultUrl = viewModel.getSavedUrl(),
                defaultToken = viewModel.getSavedToken(),
                defaultE2eeKey = viewModel.getSavedE2EEKey(),
                defaultE2eeOn = viewModel.getE2EEOptionsOn(),
                onConnect = { url, token, e2eeKey, e2eeOn, stressTest ->
                    val intent = Intent(this@MainActivity, CallActivity::class.java).apply {
                        putExtra(
                            CallActivity.KEY_ARGS,
                            CallActivity.BundleArgs(
                                url,
                                token,
                                e2eeKey,
                                e2eeOn,
                                stressTest,
                            ),
                        )
                    }
                    startActivity(intent)
                },
                onSave = { url, token, e2eeKey, e2eeOn ->
                    viewModel.setSavedUrl(url)
                    viewModel.setSavedToken(token)
                    viewModel.setSavedE2EEKey(e2eeKey)
                    viewModel.setSavedE2EEOn(e2eeOn)

                    Toast.makeText(
                        this@MainActivity,
                        "Values saved.",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onReset = {
                    viewModel.reset()
                    Toast.makeText(
                        this@MainActivity,
                        "Values reset.",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
    }

    @Preview(
        showBackground = true,
        showSystemUi = true,
    )
    @Composable
    fun MainContent(
        defaultUrl: String = MainViewModel.URL,
        defaultToken: String = MainViewModel.TOKEN,
        defaultSecondToken: String = MainViewModel.TOKEN,
        defaultE2eeKey: String = MainViewModel.E2EE_KEY,
        defaultE2eeOn: Boolean = false,
        onConnect: (url: String, token: String, e2eeKey: String, e2eeOn: Boolean, stressTest: StressTest) -> Unit = { _, _, _, _, _ -> },
        onSave: (url: String, token: String, e2eeKey: String, e2eeOn: Boolean) -> Unit = { _, _, _, _ -> },
        onReset: () -> Unit = {},
    ) {
        AppTheme {
            var url by remember { mutableStateOf(defaultUrl) }
            var token by remember { mutableStateOf(defaultToken) }
            var e2eeKey by remember { mutableStateOf(defaultE2eeKey) }
            var e2eeOn by remember { mutableStateOf(defaultE2eeOn) }
            var stressTest by remember { mutableStateOf(false) }
            var secondToken by remember { mutableStateOf(defaultSecondToken) }
            val scrollState = rememberScrollState()
            // A surface container using the 'background' color from the theme
            Surface(
                color = MaterialTheme.colors.background,
                modifier = Modifier
                    .fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .verticalScroll(scrollState),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .padding(10.dp),
                    ) {
                        Spacer(modifier = Modifier.height(50.dp))
                        Image(
                            painter = painterResource(id = R.drawable.banner_dark),
                            contentDescription = "",
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("URL") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text("Token") },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (e2eeOn) {
                            Spacer(modifier = Modifier.height(20.dp))
                            OutlinedTextField(
                                value = e2eeKey,
                                onValueChange = { e2eeKey = it },
                                label = { Text("E2EE Key") },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = e2eeOn,
                            )
                        }

                        if (stressTest) {
                            Spacer(modifier = Modifier.height(20.dp))
                            OutlinedTextField(
                                value = secondToken,
                                onValueChange = { secondToken = it },
                                label = { Text("Second token") },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = stressTest,
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Enable E2EE")
                            Switch(
                                checked = e2eeOn,
                                onCheckedChange = { e2eeOn = it },
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Stress test")
                            Switch(
                                checked = stressTest,
                                onCheckedChange = { stressTest = it },
                            )
                        }

                        Spacer(modifier = Modifier.height(40.dp))
                        Button(
                            onClick = {
                                val stressTestCmd = if (stressTest) {
                                    StressTest.SwitchRoom(token, secondToken)
                                } else {
                                    StressTest.None
                                }
                                onConnect(url, token, e2eeKey, e2eeOn, stressTestCmd)
                            },
                        ) {
                            Text("Connect")
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Button(onClick = { onSave(url, token, e2eeKey, e2eeOn) }) {
                            Text("Save Values")
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = {
                                onReset()
                                url = MainViewModel.URL
                                token = MainViewModel.TOKEN
                            },
                        ) {
                            Text("Reset Values")
                        }
                    }
                }
            }
        }
    }
}
