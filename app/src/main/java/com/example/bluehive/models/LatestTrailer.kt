package com.example.bluehive.models

import com.google.gson.annotations.SerializedName

data class LatestTrailer(
    val id: Int,
    @SerializedName("item_number")
    val itemNumber: Int,
    @SerializedName("data_id")
    val dataId: String,
    @SerializedName("data_title")
    val dataTitle: String,
    val title: String,
    @SerializedName("img_src")
    val imgSrc: String,
    @SerializedName("youtube_url")
    val youtubeUrl: String,
    @SerializedName("source_tab")
    val sourceTab: String,
    @SerializedName("media_type")
    val mediaType: String?,
    @SerializedName("scraped_at")
    val scrapedAt: String
)

data class TrailerResponse(
    @SerializedName("total_count")
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
    val trailers: List<LatestTrailer>
)