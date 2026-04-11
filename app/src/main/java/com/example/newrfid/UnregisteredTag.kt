package com.example.newrfid

data class UnregisteredTag(
    val epc: String,
    val tid: String? = null,
    val rssi: String? = null,
    val read_at: String? = null,
    var isSelected: Boolean = false
)