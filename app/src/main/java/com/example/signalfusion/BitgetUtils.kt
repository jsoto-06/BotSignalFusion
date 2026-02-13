package com.example.signalfusion

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object BitgetUtils {
    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val BASE_URL = "https://api.bitget.com"

    private fun sign(timestamp: String, method: String, path: String, body: String?, secret: String): String {
        val message = timestamp + method + path + (body ?: "")
        val secretKeySpec = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKeySpec)
        val signatureBytes = mac.doFinal(message.toByteArray())
        return Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
    }

    fun authenticatedGet(path: String, apiKey: String, secret: String, passphrase: String): String? {
        val timestamp = System.currentTimeMillis().toString()
        val signature = sign(timestamp, "GET", path, null, secret)
        val request = Request.Builder().url(BASE_URL + path)
            .addHeader("ACCESS-KEY", apiKey).addHeader("ACCESS-SIGN", signature)
            .addHeader("ACCESS-TIMESTAMP", timestamp).addHeader("ACCESS-PASSPHRASE", passphrase)
            .addHeader("Content-Type", "application/json").addHeader("locale", "en-US").get().build()
        return try { client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null } } catch (e: Exception) { null }
    }

    fun authenticatedPost(path: String, jsonBody: String, apiKey: String, secret: String, passphrase: String): String? {
        val timestamp = System.currentTimeMillis().toString()
        val signature = sign(timestamp, "POST", path, jsonBody, secret)
        val body = jsonBody.toRequestBody(JSON)
        val request = Request.Builder().url(BASE_URL + path)
            .addHeader("ACCESS-KEY", apiKey).addHeader("ACCESS-SIGN", signature)
            .addHeader("ACCESS-TIMESTAMP", timestamp).addHeader("ACCESS-PASSPHRASE", passphrase)
            .addHeader("Content-Type", "application/json").addHeader("locale", "en-US").post(body).build()
        return try { client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null } } catch (e: Exception) { null }
    }
}