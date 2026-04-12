package com.example.newrfid

data class BulkUnregisterRequest(
    val device_id: String?,
    val data: List<BulkUnregisterItem>
)

data class BulkUnregisterItem(
    val epc: String
)

data class BulkUnregisterResponse(
    val success: Boolean,
    val message: String,
    val total_requested: Int? = null,
    val total_unregistered: Int? = null,
    val total_not_found: Int? = null,
    val updated: List<BulkUnregisterUpdatedItem>? = null,
    val not_found: List<BulkUnregisterNotFoundItem>? = null
)

data class BulkUnregisterUpdatedItem(
    val epc: String,
    val item_code: String
)

data class BulkUnregisterNotFoundItem(
    val epc: String
)