package com.example.newrfid

data class UnregisteredTagsResponse(
    val success: Boolean,
    val data: List<UnregisteredTag>
)