/*
 * Copyright 2023 LiveKit, Inc.
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

package io.livekit.android.videoencodedecode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.accompanist.pager.ExperimentalPagerApi
import io.livekit.android.composesample.ui.theme.AppTheme
import io.livekit.android.sample.common.R

@ExperimentalPagerApi
class MainActivity : ComponentActivity() {

    val viewModel by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions()
        setContent {
            MainContent(
                defaultUrl = viewModel.getSavedUrl(),
                defaultToken1 = viewModel.getSavedToken1(),
                defaultToken2 = viewModel.getSavedToken2(),
                onConnect = { url, token1, token2, ->
                    val intent = Intent(this@MainActivity, CallActivity::class.java).apply {
                        putExtra(
                            CallActivity.KEY_ARGS,
                            CallActivity.BundleArgs(
                                url,
                                token1,
                                token2,
                            )
                        )
                    }
                    startActivity(intent)
                },
                onSave = { url, token1, token2 ->
                    viewModel.setSavedUrl(url)
                    viewModel.setSavedToken1(token1)
                    viewModel.setSavedToken2(token2)

                    Toast.makeText(
                        this@MainActivity,
                        "Values saved.",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onReset = {
                    viewModel.reset()
                    Toast.makeText(
                        this@MainActivity,
                        "Values reset.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
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
        defaultToken1: String = MainViewModel.TOKEN1,
        defaultToken2: String = MainViewModel.TOKEN2,
        onConnect: (url: String, token1: String, token2: String) -> Unit = { _, _, _ -> },
        onSave: (url: String, token1: String, token2: String) -> Unit = { _, _, _ -> },
        onReset: () -> Unit = {},
    ) {
        AppTheme {
            var url by remember { mutableStateOf(defaultUrl) }
            var token1 by remember { mutableStateOf(defaultToken1) }
            var token2 by remember { mutableStateOf(defaultToken2) }
            // A surface container using the 'background' color from the theme
            Surface(
                color = MaterialTheme.colors.background,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(10.dp)
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
                        value = token1,
                        onValueChange = { token1 = it },
                        label = { Text("Token1") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    OutlinedTextField(
                        value = token2,
                        onValueChange = { token2 = it },
                        label = { Text("Token2") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = { onConnect(url, token1, token2) }) {
                        Text("Connect")
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = { onSave(url, token1, token2) }) {
                        Text("Save Values")
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = {
                        onReset()
                        url = MainViewModel.URL
                        token1 = MainViewModel.TOKEN1
                        token2 = MainViewModel.TOKEN2
                    }) {
                        Text("Reset Values")
                    }
                }
            }
        }
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
