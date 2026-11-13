package com.outlierradar.app.data.remote

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface YouTubeApi {

  @GET("search")
  suspend fun searchVideos(
    @Query("part") part: String = "snippet",
    @Query("q") query: String,
    @Query("type") type: String = "video",
    @Query("videoDuration") videoDuration: String = "short",
    @Query("order") order: String = "viewCount",
    @Query("maxResults") maxResults: Int = 50,
    @Query("pageToken") pageToken: String? = null,
    @Query("publishedAfter") publishedAfter: String? = null,
    @Query("publishedBefore") publishedBefore: String? = null,
    @Query("regionCode") regionCode: String? = null,
    @Query("relevanceLanguage") relevanceLanguage: String? = null,
    @Query("key") apiKey: String
  ): Response<SearchResponse>

  @GET("videos")
  suspend fun getVideos(
    @Query("part") part: String = "snippet,statistics,contentDetails",
    @Query("id") ids: String,
    @Query("key") apiKey: String
  ): Response<VideoListResponse>

  @GET("channels")
  suspend fun getChannels(
    @Query("part") part: String = "snippet,statistics,contentDetails",
    @Query("id") ids: String,
    @Query("key") apiKey: String
  ): Response<ChannelListResponse>

  @GET("channels")
  suspend fun getChannelByHandle(
    @Query("part") part: String = "snippet,statistics,contentDetails",
    @Query("forHandle") handle: String,
    @Query("key") apiKey: String
  ): Response<ChannelListResponse>

  @GET("channels")
  suspend fun getChannelByUsername(
    @Query("part") part: String = "snippet,statistics,contentDetails",
    @Query("forUsername") username: String,
    @Query("key") apiKey: String
  ): Response<ChannelListResponse>

  @GET("playlistItems")
  suspend fun getPlaylistItems(
    @Query("part") part: String = "contentDetails,snippet",
    @Query("playlistId") playlistId: String,
    @Query("maxResults") maxResults: Int = 50,
    @Query("pageToken") pageToken: String? = null,
    @Query("key") apiKey: String
  ): Response<PlaylistItemListResponse>
}
