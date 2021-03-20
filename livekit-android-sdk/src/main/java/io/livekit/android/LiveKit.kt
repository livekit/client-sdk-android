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
            room.connect(url, token, options.isSecure)

            val localParticipant = room.localParticipant
            if (localParticipant != null) {
                val factory = component.peerConnectionFactory()
                if (options.sendAudio) {
                    localParticipant.publishAudioTrack(createLocalAudioTrack(factory))
                }
                if (options.sendVideo) {
                    localParticipant.publishVideoTrack(
                        createLocalVideoTrack(
                            factory,
                            appContext,
                            component.eglBase()
                        )
                    )
                }
            }
            return room
        }

        private fun createLocalVideoTrack(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            rootEglBase: EglBase,
        ): LocalVideoTrack {
            return LocalVideoTrack.track(
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
