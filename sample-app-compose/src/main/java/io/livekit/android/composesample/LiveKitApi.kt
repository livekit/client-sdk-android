package io.livekit.android.composesample

import retrofit2.http.GET
import retrofit2.http.Query

data class TokenResponse(val token: String)
data class Room(val name: String)
data class ListRoomsResponse(val rooms: List<Room>)

interface LiveKitApi {
    @GET("/get-token")
    suspend fun getToken(
        @Query("room") room: String,
        @Query("identity") identity: String
    ): TokenResponse

    @GET("/list-rooms")
    suspend fun listRooms(): ListRoomsResponse
}
