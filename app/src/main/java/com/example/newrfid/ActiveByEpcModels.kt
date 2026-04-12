package com.example.newrfid

data class ActiveByEpcRequest(
    val epc: String
)

data class ActiveByEpcResponse(
    val success: Boolean,
    val message: String,
    val data: ActiveTagData? = null
)

data class ActiveTagData(
    val id: Int? = null,
    val epc: String,
    val item_code: String,
    val is_active: Boolean? = null
)