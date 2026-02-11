package com.example.bitgetbot

import android.content.Context

object Prefs {
    private const val PREF_NAME = "BitgetSecurePrefs"

    fun saveKeys(context: Context, apiKey: String, secretKey: String, pass: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("API_KEY", apiKey)
            putString("SECRET_KEY", secretKey)
            putString("PASSPHRASE", pass)
            apply()
        }
    }

    fun getApiKey(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString("API_KEY", "") ?: ""

    fun getSecretKey(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString("SECRET_KEY", "") ?: ""

    fun getPassphrase(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString("PASSPHRASE", "") ?: ""
}