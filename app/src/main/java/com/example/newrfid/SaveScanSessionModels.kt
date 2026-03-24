package com.example.newrfid

import com.google.gson.annotations.SerializedName

data class SaveScanSessionRequest(
    @SerializedName("device_id")
    val deviceId: String?,

    @SerializedName("scanned_at")
    val scannedAt: String?,

    @SerializedName("total_unique_epc")
    val totalUniqueEpc: Int,

    @SerializedName("total_reads")
    val totalReads: Int,

    @SerializedName("unknown_epc")
    val unknownEpc: Int,

    @SerializedName("items")
    val items: List<SaveScanSessionItemRequest>,

    @SerializedName("tags")
    val tags: List<SaveScanSessionTagRequest>
)

data class SaveScanSessionItemRequest(
    @SerializedName("item_code")
    val itemCode: String,

    @SerializedName("item_name")
    val itemName: String?,

    @SerializedName("stock_system")
    val stockSystem: Int,

    @SerializedName("scan_qty")
    val scanQty: Int
)

data class SaveScanSessionTagRequest(
    @SerializedName("epc")
    val epc: String,

    @SerializedName("tid")
    val tid: String?,

    @SerializedName("rssi")
    val rssi: String?,

    @SerializedName("item_code")
    val itemCode: String?,

    @SerializedName("is_registered")
    val isRegistered: Boolean,

    @SerializedName("read_at")
    val readAt: String?
)

data class SaveScanSessionResponse(
    val success: Boolean,
    val message: String,
    val data: SaveScanSessionResponseData?
)

data class SaveScanSessionResponseData(
    @SerializedName("session_id")
    val sessionId: Int?,

    @SerializedName("session_code")
    val sessionCode: String?,

    @SerializedName("device_id")
    val deviceId: String?,

    @SerializedName("scanned_at")
    val scannedAt: String?,

    @SerializedName("total_unique_epc")
    val totalUniqueEpc: Int?,

    @SerializedName("total_reads")
    val totalReads: Int?,

    @SerializedName("unknown_epc")
    val unknownEpc: Int?,

    @SerializedName("saved_items")
    val savedItems: Int?,

    @SerializedName("saved_tags")
    val savedTags: Int?
)