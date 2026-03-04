package com.example.signalfusion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TelegramNotifier {

    suspend fun enviarEntrada(context: Context, symbol: String, side: String, price: Double) {
        val icon = if (side == "LONG") "🚀" else "🔻"
        val mensaje = """
            $icon *NUEVA POSICIÓN ABIERTA* $icon
            
            🪙 *Par:* $symbol
            📈 *Tipo:* $side
            💲 *Precio:* $${"%.2f".format(price)}
            🤖 *Motor:* V5.2 Sniper
        """.trimIndent()

        verificarYEnviar(context, mensaje)
    }

    suspend fun enviarSalida(context: Context, symbol: String, side: String, pnl: Double, precioCierre: Double) {
        val icon = if (pnl >= 0) "✅" else "❌"
        val profitText = if (pnl >= 0) "PROFIT" else "LOSS"

        val mensaje = """
            $icon *POSICIÓN CERRADA ($profitText)*
            
            🪙 *Par:* $symbol ($side)
            🛑 *Cierre:* $${"%.2f".format(precioCierre)}
            💰 *Resultado:* ${if (pnl > 0) "+" else ""}${"%.2f".format(pnl)} USDT
            
            ${if (pnl >= 0) "🤑 A la buchaca!" else "🛡️ Stop Loss ejecutado"}
        """.trimIndent()

        verificarYEnviar(context, mensaje)
    }

    private suspend fun verificarYEnviar(context: Context, texto: String) {
        // 1. LEER CONFIGURACIÓN DEL USUARIO
        val prefs = context.getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("TG_ENABLED", false)
        val token = prefs.getString("TG_TOKEN", "") ?: ""
        val chatId = prefs.getString("TG_CHAT_ID", "") ?: ""

        // 2. SI ESTÁ APAGADO O FALTAN DATOS, NO HACER NADA
        if (!isEnabled || token.isEmpty() || chatId.isEmpty()) return

        // 3. ENVIAR
        enviarMensajeReal(token, chatId, texto)
    }

    private suspend fun enviarMensajeReal(token: String, chatId: String, texto: String) {
        withContext(Dispatchers.IO) {
            try {
                val textoSeguro = URLEncoder.encode(texto, "UTF-8")
                val urlString = "https://api.telegram.org/bot$token/sendMessage?chat_id=$chatId&text=$textoSeguro&parse_mode=Markdown"

                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.connect()

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    println("Error Telegram: $responseCode")
                }
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}