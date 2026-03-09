package com.example.newrfid

data class ProductRow(
    val itemCode: String,
    val itemName: String,
    val endStock: Int,
    var scanQty: Int = 0
) {
    fun diff(): Int = scanQty - endStock

    fun status(): String {
        return when {
            scanQty == endStock -> "MATCH"
            scanQty > endStock -> "MORE"
            else -> "LESS"
        }
    }

    fun isMismatch(): Boolean = scanQty != endStock
}