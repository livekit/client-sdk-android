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

package io.livekit.android.sample.record

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.sample.record.ui.theme.LivekitandroidTheme
import io.livekit.android.sample.util.requestNeededPermissions
import kotlinx.coroutines.launch
import livekit.org.webrtc.EglBase
import java.io.File
import java.io.IOException
import java.util.Date

class MainActivity : ComponentActivity() {
    lateinit var room: Room
    var videoFileRenderer: VideoFileRenderer? = null
    val connected = MutableLiveData(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create Room object.
        room = LiveKit.create(
            appContext = applicationContext,
            overrides = LiveKitOverrides(
                audioOptions = AudioOptions(
                    javaAudioDeviceModuleCustomizer = { builder ->
                        // Receive audio samples
                        builder.setSamplesReadyCallback { samples ->
                            videoFileRenderer?.onWebRtcAudioRecordSamplesReady(samples)
                        }
                    }
                ),
            )
        )

        setContent {
            LivekitandroidTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
                    Column {
                        val isConnected by connected.observeAsState(false)

                        if (isConnected) {
                            Text(text = "Connected!")
                            Button(onClick = { disconnectRoom() }) {
                                Text("Disconnect")
                            }
                        } else {
                            Text(text = "Not Connected.")
                            Button(onClick = { connectToRoom() }) {
                                Text("Connect")
                            }
                        }
                    }
                }
            }
        }

        requestNeededPermissions()
    }

    private fun connectToRoom() {
        val url = "wss://www.example.com"
        val token = ""

        lifecycleScope.launch {
            // Connect to server.
            room.connect(
                url,
                token,
            )

            val localParticipant = room.localParticipant
            localParticipant.setMicrophoneEnabled(true)
            localParticipant.setCameraEnabled(true)

            // Create output file.
            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val file = File(dir, "${Date().time}.mp4")
            if (!file.createNewFile()) {
                throw IOException()
            }

            // Setup video recording
            val videoFileRenderer = VideoFileRenderer(
                file.absolutePath,
                EglBase.create().eglBaseContext,
                true
            )
            this@MainActivity.videoFileRenderer = videoFileRenderer

            // Attach to local video track.
            val track = localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as LocalVideoTrack
            track.addRenderer(videoFileRenderer)

            connected.value = true
        }
    }

    fun disconnectRoom() {
        room.disconnect()
        videoFileRenderer?.release()
        videoFileRenderer = null
        connected.value = false
    }
}
