package com.example.newrfid

import com.google.gson.annotations.SerializedName

data class PushTagsRequest(
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("tags") val tags: List<TagItem>
)

data class TagItem(
    val epc: String,
    val tid: String? = null,
    val rssi: String? = null,
    @SerializedName("read_at") val readAt: String? = null
)

data class PushTagsResponse(
    val success: Boolean,
    val message: String?,
    val inserted: Int?,
    val duplicate: Int?,
    @SerializedName("duplicate_epc") val duplicateEpc: List<String>?
)