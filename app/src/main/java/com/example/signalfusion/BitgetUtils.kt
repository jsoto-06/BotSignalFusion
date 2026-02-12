package com.example.signalfusion

import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object BitgetUtils {

    // Esta función crea la firma digital que exige Bitget
    fun generateSignature(timestamp: String, method: String, path: String, secretKey: String): String {
        try {
            // La fórmula es: Timestamp + Método + URL + Body (si hay)
            val message = timestamp + method + path

            val hmac = Mac.getInstance("HmacSHA256")
            val secretSpec = SecretKeySpec(secretKey.toByteArray(), "HmacSHA256")
            hmac.init(secretSpec)

            val signatureBytes = hmac.doFinal(message.toByteArray())
            // Convertimos a Base64
            return Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }
}