package io.livekit.android.sample.basic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        requestNeededPermissions { connectToRoom() }
    }


    private fun requestNeededPermissions(onHasPermissions: () -> Unit) {
        val requestPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->

                var hasDenied = false
                // Check if any permissions weren't granted.
                for (grant in grants.entries) {
                    if (!grant.value) {
                        Toast.makeText(this, "Missing permission: ${grant.key}", Toast.LENGTH_SHORT)
                            .show()

                        hasDenied = true
                    }
                }

                if (!hasDenied) {
                    onHasPermissions()
                }
            }

        val neededPermissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .let { perms ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Need BLUETOOTH_CONNECT permission on API S+ to output to bluetooth devices.
                    perms + listOf(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    perms
                }
            }
            .filter { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_DENIED }
            .toTypedArray()

        if (neededPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(neededPermissions)
        } else {
            onHasPermissions()
        }
    }

    private fun connectToRoom() {


        //val url = "wss://your_host"
        //val token = "your_token"

        val url = "ws://192.168.11.5:7880"
        val token =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE2NjEzNTExODMsImlzcyI6IkFQSVNRdmdrYWJZdXFUQSIsIm5iZiI6MTY1ODc1OTE4Mywic3ViIjoicGhvbmUiLCJ2aWRlbyI6eyJyb29tIjoibXlyb29tIiwicm9vbUpvaW4iOnRydWV9fQ.TOPMBzqqr2TWHjadleFisY5C0_Zwaowj1EVS_7iVcRE"

        lifecycleScope.launch {
            // Create Room object.
            val room = LiveKit.create(applicationContext)

            // Setup the video renderer
            room.initVideoRenderer(findViewById<SurfaceViewRenderer>(R.id.renderer))

            // Setup event handling.
            launch {
                room.events.collect { event ->
                    when (event) {
                        is RoomEvent.TrackSubscribed -> onTrackSubscribed(event)
                        else -> {}
                    }
                }
            }

            // Connect to server.
            room.connect(
                url,
                token,
            )

            // Turn on audio/video recording.
            val localParticipant = room.localParticipant
            localParticipant.setMicrophoneEnabled(true)
            localParticipant.setCameraEnabled(true)
        }
    }

    private fun onTrackSubscribed(event: RoomEvent.TrackSubscribed) {
        val track = event.track
        if (track is VideoTrack) {
            attachVideo(track)
        }
    }

    private fun attachVideo(videoTrack: VideoTrack) {
        videoTrack.addRenderer(findViewById<SurfaceViewRenderer>(R.id.renderer))
        findViewById<View>(R.id.progress).visibility = View.GONE
    }
}