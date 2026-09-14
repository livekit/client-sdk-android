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

package io.livekit.android.room.participant

import io.livekit.android.room.datatrack.DataTrackSid
import io.livekit.android.room.datatrack.RemoteDataTrack
import io.livekit.android.util.MutableStateFlowDelegate
import io.livekit.android.util.flowDelegate

/**
 * Bookkeeping for the data tracks attached to a [RemoteParticipant].
 *
 * Owns the delegate backing [RemoteParticipant.dataTracks], so the participant can expose the
 * observable property without also owning the mutation logic.
 */
internal class RemoteDataTrackCollection(
    private val onPublished: (RemoteDataTrack) -> Unit,
    private val onUnpublished: (DataTrackSid) -> Unit,
) {
    private val lock = Any()

    /**
     * Backing delegate for [RemoteParticipant.dataTracks].
     */
    val delegate: MutableStateFlowDelegate<Map<String, RemoteDataTrack>> = flowDelegate(emptyMap())

    private var tracks: Map<String, RemoteDataTrack> by delegate

    /**
     * Adds the track, returning `false` if this exact track is already attached.
     */
    fun add(track: RemoteDataTrack): Boolean {
        val attached = synchronized(lock) {
            if (tracks.values.any { it === track }) {
                return@synchronized false
            }
            tracks = tracks + (track.name to track)
            true
        }
        if (attached) {
            onPublished(track)
        }
        return attached
    }

    fun remove(sid: DataTrackSid): RemoteDataTrack? {
        // `info.sid` is an FFI call; resolve the instance before taking the lock.
        val track = tracks.values.firstOrNull { it.info.sid == sid } ?: return null
        synchronized(lock) {
            if (tracks.values.none { it === track }) {
                return null
            }
            tracks = tracks - track.name
            return track
        }
    }

    /**
     * Removes the track and notifies, even if it was not attached (for example after a full
     * reconnect detached it).
     */
    fun unpublish(sid: DataTrackSid) {
        remove(sid)
        onUnpublished(sid)
    }

    /**
     * Unpublishes every attached data track and notifies for each.
     *
     * @return The SIDs that were unpublished.
     */
    fun unpublishAll(): List<DataTrackSid> {
        val previous = synchronized(lock) {
            tracks.also { tracks = emptyMap() }
        }
        val sids = previous.values.map { it.info.sid }
        for (sid in sids) {
            onUnpublished(sid)
        }
        return sids
    }

    /**
     * Drops attached data tracks without notifying.
     */
    fun detachAll() {
        synchronized(lock) {
            tracks = emptyMap()
        }
    }
}

/**
 * Adds the track, returning `false` if this exact track is already attached.
 */
internal fun RemoteParticipant.addDataTrack(track: RemoteDataTrack): Boolean =
    dataTrackCollection.add(track)

internal fun RemoteParticipant.removeDataTrack(sid: DataTrackSid): RemoteDataTrack? =
    dataTrackCollection.remove(sid)

/**
 * Removes the track and emits [io.livekit.android.events.ParticipantEvent.DataTrackUnpublished],
 * even if it was not attached (for example after a full reconnect detached it).
 */
internal fun RemoteParticipant.unpublishDataTrack(sid: DataTrackSid) {
    dataTrackCollection.unpublish(sid)
}

/**
 * Unpublishes every attached data track and emits an unpublish event for each.
 *
 * @return The SIDs that were unpublished, for the room to emit matching
 *   [io.livekit.android.events.RoomEvent]s.
 */
internal fun RemoteParticipant.unpublishDataTracks(): List<DataTrackSid> =
    dataTrackCollection.unpublishAll()

/**
 * Drops attached data tracks without notifying. Used when the tracks outlive this participant
 * object: a full reconnect recreates participants, but the incoming manager keeps its tracks
 * and re-attaches them.
 */
internal fun RemoteParticipant.detachDataTracks() {
    dataTrackCollection.detachAll()
}
