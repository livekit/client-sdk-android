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

package io.livekit.android.example.screenshareaudio

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.livekit.android.LiveKit
import io.livekit.android.example.screenshareaudio.ui.theme.LivekitandroidTheme
import io.livekit.android.util.LoggingLevel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels<MainViewModel>()
    private val screenCaptureIntentLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val resultCode = result.resultCode
            val data = result.data
            if (resultCode != RESULT_OK || data == null) {
                return@registerForActivityResult
            }
            viewModel.startScreenCapture(data)
        }

    private fun requestMediaProjection() {
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureIntentLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveKit.loggingLevel = LoggingLevel.INFO
        viewModel
        setContent {
            LivekitandroidTheme {
                // A surface container using the 'background' color from the theme
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var enableScreenCapture by remember {
                        mutableStateOf(true)
                    }
                    Button(
                        onClick = {
                            if (enableScreenCapture) {
                                requestMediaProjection()
                            } else {
                                viewModel.stopScreenCapture()
                            }
                            enableScreenCapture = !enableScreenCapture
                        },
                    ) {
                        val text = if (enableScreenCapture) {
                            "enable"
                        } else {
                            "disable"
                        }
                        Text(text = text)
                    }
                }
            }
        }
    }
}
