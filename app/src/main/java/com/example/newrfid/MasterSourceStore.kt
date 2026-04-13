package com.example.newrfid

import android.content.Context

object MasterSourceStore {
    private const val PREF_NAME = "rfid_pref"
    private const val KEY_MASTER_SOURCE = "master_source"

    fun save(context: Context, source: MasterSource) {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        pref.edit().putString(KEY_MASTER_SOURCE, source.apiValue).apply()
    }

    fun load(context: Context): MasterSource {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return when (pref.getString(KEY_MASTER_SOURCE, MasterSource.DEFAULT.apiValue)) {
            MasterSource.IMPORT.apiValue -> MasterSource.IMPORT
            else -> MasterSource.DEFAULT
        }
    }
}