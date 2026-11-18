package eu.bradshaw.seidr.data.api

import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The Subsonic REST endpoints Seiðr calls. Auth and the format parameters are
 * added by [SubsonicClient]'s interceptor, and the base URL is rewritten to the
 * configured server there too, so these paths are relative to `/rest/`.
 *
 * Writes use POST forms (the OpenSubsonic `formPost` extension) so that long
 * song-id lists never hit URL length limits.
 */
interface SubsonicApi {
    @GET("ping.view") suspend fun ping(): Envelope

    @GET("getOpenSubsonicExtensions.view") suspend fun getOpenSubsonicExtensions(): Envelope

    @GET("getRandomSongs.view") suspend fun getRandomSongs(@Query("size") size: Int): Envelope

    @GET("getUser.view") suspend fun getUser(@Query("username") username: String): Envelope

    @GET("getPlaylists.view") suspend fun getPlaylists(): Envelope
    @GET("getPlaylist.view") suspend fun getPlaylist(@Query("id") id: String): Envelope

    @FormUrlEncoded @POST("createPlaylist.view")
    suspend fun createPlaylist(@Field("name") name: String, @Field("songId") songIds: List<String>): Envelope

    /** With playlistId, Subsonic replaces the playlist's whole song list. Only used for reorder. */
    @FormUrlEncoded @POST("createPlaylist.view")
    suspend fun replacePlaylistSongs(@Field("playlistId") playlistId: String, @Field("songId") songIds: List<String>): Envelope

    @FormUrlEncoded @POST("updatePlaylist.view")
    suspend fun updatePlaylist(
        @Field("playlistId") playlistId: String,
        @Field("name") name: String? = null,
        @Field("songIdToAdd") songIdsToAdd: List<String> = emptyList(),
        @Field("songIndexToRemove") songIndexesToRemove: List<Int> = emptyList(),
        @Field("public") public: Boolean? = null,
    ): Envelope

    @FormUrlEncoded @POST("deletePlaylist.view")
    suspend fun deletePlaylist(@Field("id") id: String): Envelope

    @GET("getArtists.view") suspend fun getArtists(): Envelope
    @GET("getArtist.view") suspend fun getArtist(@Query("id") id: String): Envelope
    @GET("getAlbum.view") suspend fun getAlbum(@Query("id") id: String): Envelope
    @GET("getAlbumInfo2.view") suspend fun getAlbumInfo2(@Query("id") id: String): Envelope
    @GET("getSong.view") suspend fun getSong(@Query("id") id: String): Envelope

    @GET("getAlbumList2.view")
    suspend fun getAlbumList2(@Query("type") type: String, @Query("size") size: Int, @Query("offset") offset: Int = 0): Envelope

    @GET("getStarred2.view") suspend fun getStarred2(): Envelope

    @GET("getScanStatus.view") suspend fun getScanStatus(): Envelope

    @GET("search3.view")
    suspend fun search3(
        @Query("query") query: String,
        @Query("artistCount") artistCount: Int = 5,
        @Query("albumCount") albumCount: Int = 10,
        @Query("songCount") songCount: Int = 20,
    ): Envelope

    @GET("getTopSongs.view")
    suspend fun getTopSongs(@Query("artist") artist: String, @Query("count") count: Int = 10): Envelope

    /** OpenSubsonic `songLyrics` extension: synced and unsynced lyrics for one song. */
    @GET("getLyricsBySongId.view") suspend fun getLyricsBySongId(@Query("id") id: String): Envelope

    /** Plain Subsonic lyrics, matched by artist and title. Fallback for servers without the extension. */
    @GET("getLyrics.view")
    suspend fun getLyrics(@Query("artist") artist: String, @Query("title") title: String): Envelope

    @GET("getBookmarks.view") suspend fun getBookmarks(): Envelope

    @FormUrlEncoded @POST("createBookmark.view")
    suspend fun createBookmark(@Field("id") id: String, @Field("position") position: Long, @Field("comment") comment: String): Envelope

    @FormUrlEncoded @POST("deleteBookmark.view") suspend fun deleteBookmark(@Field("id") id: String): Envelope

    @FormUrlEncoded @POST("star.view") suspend fun star(@Field("id") id: String): Envelope
    @FormUrlEncoded @POST("unstar.view") suspend fun unstar(@Field("id") id: String): Envelope

    @FormUrlEncoded @POST("scrobble.view")
    suspend fun scrobble(@Field("id") id: String, @Field("time") time: Long, @Field("submission") submission: Boolean): Envelope
}
