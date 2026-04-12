package com.example.newrfid

data class UnregTagItem(
    val epc: String,
    val item_code: String,
    var isSelected: Boolean = true
)