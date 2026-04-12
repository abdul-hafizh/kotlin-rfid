package com.example.newrfid

data class UnregItem(
    val epc: String,
    val itemCode: String,
    val itemName: String,
    var isSelected: Boolean = true
)