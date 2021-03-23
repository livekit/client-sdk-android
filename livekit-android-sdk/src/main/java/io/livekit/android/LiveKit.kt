package io.livekit.android

import android.content.Context
import io.livekit.android.dagger.DaggerLiveKitComponent
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory

class LiveKit {
    companion object {
        /**
         * Connect to a LiveKit room
         * @param url URL to LiveKit server (i.e. ws://mylivekitdeploy.io)
         * @param listener Listener to Room events. LiveKit interactions take place with these callbacks
         */
        suspend fun connect(
            appContext: Context,
            url: String,
            token: String,
            options: ConnectOptions,
            listener: Room.Listener?
        ): Room {

            val component = DaggerLiveKitComponent
                .factory()
                .create(appContext)

            val room = component.roomFactory()
                .create(options)
            room.listener = listener
            room.connect(url, token)

            val localParticipant = room.localParticipant
            if (localParticipant != null) {
                val factory = component.peerConnectionFactory()
                if (options.sendAudio) {
                    val audioTrack = createLocalAudioTrack(factory)
                    localParticipant.publishAudioTrack(audioTrack)
                    audioTrack.enabled = true
                }
                if (options.sendVideo) {
                    val videoTrack = createLocalVideoTrack(
                        factory,
                        appContext,
                        component.eglBase()
                    )
                    localParticipant.publishVideoTrack(videoTrack)
                    videoTrack.startCapture()
                }
            }
            return room
        }

        private fun createLocalVideoTrack(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            rootEglBase: EglBase,
        ): LocalVideoTrack {
            return LocalVideoTrack.createTrack(
                peerConnectionFactory,
                context,
                true,
                "LiveKit Video",
                rootEglBase
            )
        }

        private fun createLocalAudioTrack(factory: PeerConnectionFactory): LocalAudioTrack {
            val audioConstraints = MediaConstraints()
            return LocalAudioTrack.createTrack(factory, audioConstraints)
        }
    }
}
