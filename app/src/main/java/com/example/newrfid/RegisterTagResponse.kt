package com.example.newrfid

data class RegisterTagResponse(
    val success: Boolean,
    val message: String,
    val total_requested: Int? = null,
    val total_inserted: Int? = null,
    val total_skipped: Int? = null,
    val skipped: List<SkippedTag>? = null
)

data class SkippedTag(
    val epc: String,
    val reason: String
)