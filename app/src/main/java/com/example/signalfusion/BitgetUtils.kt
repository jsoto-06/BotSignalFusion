package com.example.signalfusion

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.time.Instant

object BitgetUtils {

    private val client = OkHttpClient()
    private const val BASE_URL = "https://api.bitget.com"

    fun authenticatedPost(endpoint: String, jsonBody: String, apiKey: String, secretKey: String, passphrase: String): String? {
        return try {
            val timestamp = System.currentTimeMillis().toString()
            val signature = generateSignature(timestamp, "POST", endpoint, jsonBody, secretKey)

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = jsonBody.toRequestBody(mediaType)

            val request = Request.Builder()
                .url(BASE_URL + endpoint)
                .post(body)
                .addHeader("ACCESS-KEY", apiKey)
                .addHeader("ACCESS-SIGN", signature)
                .addHeader("ACCESS-TIMESTAMP", timestamp)
                .addHeader("ACCESS-PASSPHRASE", passphrase)
                .addHeader("Content-Type", "application/json")
                .addHeader("locale", "en-US")
                .build()

            val response = client.newCall(request).execute()
            val responseString = response.body?.string()

            // 🔥 LOG CRÍTICO: Aquí veremos qué dice Bitget realmente
            Log.d("BitgetAPI", "POST $endpoint | Code: ${response.code} | Body: $responseString")

            if (!response.isSuccessful) {
                Log.e("BitgetAPI", "❌ FAILED: $responseString")
                // Devolvemos el error para verlo en el log del bot también
                return responseString
            }
            responseString
        } catch (e: Exception) {
            Log.e("BitgetAPI", "Exception POST: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    fun authenticatedGet(endpoint: String, apiKey: String, secretKey: String, passphrase: String): String? {
        return try {
            val timestamp = System.currentTimeMillis().toString()
            // GET requests no tienen body, pasamos null o string vacío en la firma depende de la doc, Bitget suele ser query params
            // Para simplificar, en GET la firma usa query params si existen.
            // Asumimos endpoint ya trae los params (ej: /api/v2...?symbol=BTC)
            val signature = generateSignature(timestamp, "GET", endpoint, null, secretKey)

            val request = Request.Builder()
                .url(BASE_URL + endpoint)
                .get()
                .addHeader("ACCESS-KEY", apiKey)
                .addHeader("ACCESS-SIGN", signature)
                .addHeader("ACCESS-TIMESTAMP", timestamp)
                .addHeader("ACCESS-PASSPHRASE", passphrase)
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val responseString = response.body?.string()

            // Log ligero para GET (para no saturar)
            if (!response.isSuccessful) Log.e("BitgetAPI", "GET Error: $responseString")

            responseString
        } catch (e: Exception) {
            Log.e("BitgetAPI", "Exception GET: ${e.message}")
            null
        }
    }

    private fun generateSignature(timestamp: String, method: String, requestPath: String, body: String?, secret: String): String {
        return try {
            // Formato Bitget: timestamp + method + requestPath + (body o "")
            val preHash = timestamp + method.uppercase() + requestPath + (body ?: "")
            val secretKeySpec = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(secretKeySpec)
            val hash = mac.doFinal(preHash.toByteArray())
            Base64.encodeToString(hash, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
}