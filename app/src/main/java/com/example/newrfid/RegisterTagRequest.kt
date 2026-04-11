package com.example.newrfid

data class RegisterTagRequest(
    val device_id: String?,
    val data: List<RegisterTagItem>
)

data class RegisterTagItem(
    val epc: String,
    val item_code: String,
    val notes: String? = null
)