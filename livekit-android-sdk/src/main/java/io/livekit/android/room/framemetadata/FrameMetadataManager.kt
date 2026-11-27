/*
 * Copyright 2026 LiveKit, Inc.
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

package io.livekit.android.room.framemetadata

import io.livekit.android.room.track.FrameMetadataPublishOptions
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.track.hasFeatures
import io.livekit.android.util.LKLog
import livekit.org.webrtc.FrameMetadataTransformer
import livekit.org.webrtc.FrameTransformerChain
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the frame metadata transformers of a room's video tracks, and any chain built around one.
 *
 * It creates and disposes them; [FrameTransformerInstaller] installs them. Only video tracks get
 * one: a published track when its options turn on a field, a subscribed track when its publisher
 * sends metadata.
 *
 * Anything that uses a transformer holds the lock its disposal waits on: this manager's lock for
 * receive transformers, the track's for send transformers (see
 * [LocalVideoTrack.withFrameMetadataTransformer]). This manager's lock is taken before a track's,
 * never after.
 *
 * @suppress
 */
@Singleton
class FrameMetadataManager
@Inject
internal constructor(
    private val factory: FrameTransformerFactory,
) {
    private class Entry(val transformer: FrameMetadataTransformer) {
        var chain: FrameTransformerChain? = null
    }

    private val lock = Any()
    private val published = mutableMapOf<LocalVideoTrack, Entry>()
    private val subscribed = mutableMapOf<RemoteVideoTrack, Entry>()

    /**
     * Sets up a track that's about to be published: creates its send transformer, links it to the
     * track's source and starts stamping. Call before the publication becomes visible, so a backup
     * codec sender created right after can find the transformer.
     */
    internal fun addPublishedTrack(track: LocalVideoTrack, options: FrameMetadataPublishOptions?) {
        if (options == null || !options.hasFeatures()) {
            return
        }
        synchronized(lock) {
            if (published.containsKey(track)) {
                return
            }
            val transformer = factory.createFrameMetadataTransformer(FrameMetadataTransformer.Direction.SEND)
            track.attachFrameMetadataTransformer(transformer)
            track.frameMetadataStampState.arm(options)
            if (track.frameMetadataStamper == null) {
                LKLog.w { "Track ${track.name} has no frame metadata stamping processor; only values set on its frames will be sent." }
            }
            published[track] = Entry(transformer)
        }
    }

    internal fun removePublishedTrack(track: Track) {
        val entry = synchronized(lock) { published.remove(track) } ?: return
        val localTrack = track as LocalVideoTrack
        localTrack.frameMetadataStampState.disarm()
        localTrack.detachFrameMetadataTransformer()
        entry.chain?.dispose()
        entry.transformer.dispose()
    }

    /**
     * Creates the receive transformer for a subscribed track if its publisher sends metadata, and
     * hands it to the track for lookups.
     *
     * @return the transformer to install, or null if the track doesn't get one.
     */
    internal fun addSubscribedTrack(track: Track, publication: TrackPublication): FrameMetadataTransformer? {
        if (track !is RemoteVideoTrack) {
            return null
        }
        if (publication.trackInfo?.packetTrailerFeaturesList.isNullOrEmpty()) {
            return null
        }
        // The client isn't told when the server stops sending a track, so a resubscribe arrives
        // with the earlier subscription's track still here. Every subscribe builds a new track
        // object, so that track is found by sid. This also gives a track added twice a fresh
        // transformer.
        val previousTracks = synchronized(lock) { subscribed.keys.filter { it.sid == publication.sid } }
        previousTracks.forEach { removeSubscribedTrack(it) }
        val transformer = factory.createFrameMetadataTransformer(FrameMetadataTransformer.Direction.RECEIVE)
        synchronized(lock) {
            subscribed[track] = Entry(transformer)
        }
        track.setFrameMetadataTransformer(transformer)
        return transformer
    }

    /**
     * Runs [install] with [track]'s receive transformer, or null if it has none, and keeps the
     * chain it returns as [setChain] does.
     *
     * It all runs under the lock, so [removeSubscribedTrack] can't dispose the transformer while
     * it's being installed: either the track was removed first and [install] gets null, or the
     * removal waits until the chain is kept and then disposes it too. So [install] must not wait
     * on the RTC thread or call app code.
     */
    internal fun installReceive(track: Track, install: (FrameMetadataTransformer?) -> FrameTransformerChain?) {
        val unused = synchronized(lock) {
            val entry = (track as? RemoteVideoTrack)?.let { subscribed[it] }
            keepChain(entry, install(entry?.transformer))
        }
        unused?.dispose()
    }

    internal fun removeSubscribedTrack(track: Track) {
        val entry = synchronized(lock) { subscribed.remove(track) } ?: return
        // Stops lookups before disposing. A disabled receive transformer stops stripping trailers,
        // so it's disposed without being disabled first.
        (track as RemoteVideoTrack).setFrameMetadataTransformer(null)
        entry.chain?.dispose()
        entry.transformer.dispose()
    }

    /**
     * Keeps the chain installed on [track]'s sender or receiver so it's disposed with the track's
     * transformer. Replaces and disposes a previous chain. A track without a metadata transformer
     * never has a chain, and one that was removed in the meantime has its chain disposed here.
     */
    internal fun setChain(track: Track, chain: FrameTransformerChain?) {
        val unused = synchronized(lock) {
            val entry = when (track) {
                is LocalVideoTrack -> published[track]
                is RemoteVideoTrack -> subscribed[track]
                else -> null
            }
            keepChain(entry, chain)
        }
        unused?.dispose()
    }

    /**
     * Keeps [chain] on [entry], and returns the chain to dispose: [chain] itself if the track has
     * no entry, or the chain it replaces. Call it under the lock, in the same block that found
     * [entry], so a removal can't take the entry and read its chain in between.
     */
    private fun keepChain(entry: Entry?, chain: FrameTransformerChain?): FrameTransformerChain? {
        if (entry == null) {
            return chain
        }
        val previous = entry.chain
        entry.chain = chain
        return previous?.takeIf { it !== chain }
    }

    /** Disposes everything left, for a room that's disconnecting. */
    internal fun cleanup() {
        val publishedTracks = synchronized(lock) { published.keys.toList() }
        val subscribedTracks = synchronized(lock) { subscribed.keys.toList() }
        publishedTracks.forEach { removePublishedTrack(it) }
        subscribedTracks.forEach { removeSubscribedTrack(it) }
    }
}
