package com.example.bitgetbot

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class TradingService : Service() {

    companion object {
        var isRunning = false
        var currentBalance = 500.0
        var logHistory = StringBuilder()
        var lastPrice = "Conectando..."
        var currentStatus = "Iniciando motor..."
        var totalTrades = 0
        var tradesGanados = 0
        var tradesPerdidos = 0
    }

    private val client = OkHttpClient()
    private var job: Job? = null
    private val CHANNEL_ID = "BitgetBotChannel"

    private var targetTP = 2.0
    private var targetSL = 1.5
    private var leverage = 5.0
    private var isTurbo = false
    private var tamanoPosicionUSDT = 0.0

    private val historialPrecios = mutableListOf<Double>()
    private val historialVolumen = mutableListOf<Double>()
    private var posicionAbierta = false
    private var precioEntrada = 0.0
    private var tipoPosicion = ""
    private var maxPnLAlcanzado = 0.0
    private var ultimaOperacionTime = 0L
    private var saldoInicial = 0.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            sincronizarAjustes()
            isRunning = true
            saldoInicial = currentBalance

            val notificacion = createNotification("SignalFusion Pro", "Escaneando mercado...")

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notificacion, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, notificacion)
            }

            iniciarCicloTrading()
        }
        return START_STICKY
    }

    private fun sincronizarAjustes() {
        val prefs = getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        currentBalance = prefs.getString("AMOUNT", "500")?.toDoubleOrNull() ?: 500.0
        targetTP = prefs.getString("TP_VAL", "2.0")?.toDoubleOrNull() ?: 2.0
        targetSL = prefs.getString("SL_VAL", "1.5")?.toDoubleOrNull() ?: 1.5
        leverage = prefs.getInt("LEVERAGE", 5).toDouble()
        isTurbo = prefs.getBoolean("TURBO_MODE", false)
        tamanoPosicionUSDT = currentBalance * 0.02
    }

    private fun iniciarCicloTrading() {
        job = CoroutineScope(Dispatchers.IO).launch {
            agregarLog("🚀 SignalFusion Pro v2.1 Iniciado")
            agregarLog("💰 Capital: $%.2f | Riesgo: 2%% | Leverage: ${leverage}x".format(currentBalance))
            agregarLog("🎯 TP: ${targetTP}%% | SL: ${targetSL}%% | Modo: ${if (isTurbo) "TURBO ⚡" else "NORMAL 🐢"}")
            agregarLog("═══════════════════════════════════════")

            descargarHistorialInicial()

            while (isActive) {
                try {
                    val precioActual = obtenerPrecioReal()
                    val volumenActual = obtenerVolumenReal()

                    if (precioActual > 0) {
                        actualizarDatos(precioActual, volumenActual)

                        if (historialPrecios.size >= 50) {
                            ejecutarEstrategia(precioActual, volumenActual)
                        } else {
                            val progreso = (historialPrecios.size * 100) / 50
                            actualizarEstadoUI("📥 Cargando datos: ${historialPrecios.size}/50 (${progreso}%%)")
                        }
                    } else {
                        actualizarEstadoUI("⚠️ Error obteniendo precio. Reintentando...")
                    }

                } catch (e: Exception) {
                    agregarLog("❌ Error en ciclo: ${e.message}")
                }

                delay(if (isTurbo) 3000 else 6000)
            }
        }
    }

    private fun ejecutarEstrategia(precio: Double, volumen: Double) {
        val rsi = Indicadores.calcularRSI(historialPrecios, 14)
        val ema9 = Indicadores.calcularEMA(historialPrecios, 9)
        val ema21 = Indicadores.calcularEMA(historialPrecios, 21)
        val atr = Indicadores.calcularATR(historialPrecios, 14)
        val volPromedio = historialVolumen.takeLast(20).average()
        val volatilidad = (atr / precio) * 100

        if (!posicionAbierta) {
            val cooldownOk = System.currentTimeMillis() - ultimaOperacionTime > 60000
            val volFuerte = volumen > volPromedio * 1.2
            val volumenValido = volPromedio > 0 && volumen > 0
            val volatilNoExtrema = volatilidad < 3.0

            if (!cooldownOk) {
                val segs = (60 - ((System.currentTimeMillis() - ultimaOperacionTime) / 1000))
                actualizarEstadoUI("⏳ Cooldown: ${segs}s | Vol: %.2f%%".format(volatilidad))
                return
            }

            val rsiBajo = if (isTurbo) rsi < 35 else rsi < 28
            val tendenciaAlcista = ema9 > ema21
            val precioEnSoporte = precio > ema9 * 0.998 && precio < ema9 * 1.005

            val señalLong = cooldownOk && volumenValido && volFuerte &&
                    rsiBajo && precioEnSoporte && tendenciaAlcista && volatilNoExtrema

            val rsiAlto = if (isTurbo) rsi > 65 else rsi > 72
            val tendenciaBajista = ema9 < ema21
            val precioEnResistencia = precio < ema9 * 1.002 && precio > ema9 * 0.995

            val señalShort = cooldownOk && volumenValido && volFuerte &&
                    rsiAlto && precioEnResistencia && tendenciaBajista && volatilNoExtrema

            if (señalLong) {
                abrirTrade(precio, "LONG", volatilidad)
            } else if (señalShort) {
                abrirTrade(precio, "SHORT", volatilidad)
            } else {
                val trend = if (ema9 > ema21) "↗️ Alc" else "↘️ Baj"
                val volStatus = if (volFuerte) "🔥" else "💤"
                val volEmoji = if (volatilNoExtrema) "✅" else "⚠️"

                actualizarEstadoUI("🔍 RSI:%.0f | %s | Vol:%s%.1f%% | %s".format(
                    rsi, trend, volEmoji, volatilidad, volStatus
                ))
            }

        } else {
            val pnl = calcularPnL(precio)

            if (pnl > maxPnLAlcanzado) {
                maxPnLAlcanzado = pnl
            }

            val emoji = when {
                pnl >= targetTP * 0.8 -> "🚀"
                pnl > 0 -> "📈"
                pnl > -targetSL * 0.5 -> "⚠️"
                else -> "🔴"
            }

            actualizarEstadoUI("$emoji $tipoPosicion %.2f | PnL:%.2f%% Max:%.2f%%".format(
                precioEntrada, pnl, maxPnLAlcanzado
            ))

            when {
                pnl >= targetTP -> cerrarTrade(precio, "✅ Take Profit")
                pnl <= -targetSL -> cerrarTrade(precio, "🛑 Stop Loss")
                maxPnLAlcanzado >= 1.0 && (maxPnLAlcanzado - pnl) >= 0.5 -> cerrarTrade(precio, "🛡️ Trailing (desde %.2f%%)".format(maxPnLAlcanzado))
                tipoPosicion == "LONG" && rsi > 70 && ema9 < ema21 -> cerrarTrade(precio, "🔄 Giro bajista")
                tipoPosicion == "SHORT" && rsi < 30 && ema9 > ema21 -> cerrarTrade(precio, "🔄 Giro alcista")
                volatilidad > 5.0 -> cerrarTrade(precio, "⚡ Volatilidad extrema (%.1f%%)".format(volatilidad))
            }
        }

        lastPrice = "%.2f".format(precio)
    }

    private suspend fun obtenerPrecioReal(): Double = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            // CAMBIO: Quitamos "_UMCBL". Dejamos solo BTCUSDT
            .url("https://api.bitget.com/api/v2/mix/market/ticker?symbol=BTCUSDT&productType=USDT-FUTURES")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val data = json.getJSONArray("data").getJSONObject(0)
                    data.getString("lastPr").toDouble()
                } else {
                    // Esto saldrá en el log si falla, para saber por qué
                    agregarLog("⚠️ Error Precio ${response.code}: ${response.message}")
                    0.0
                }
            }
        } catch (e: Exception) {
            agregarLog("❌ Error Red: ${e.message}")
            0.0
        }
    }

    private suspend fun obtenerVolumenReal(): Double = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            // CAMBIO: Quitamos "_UMCBL"
            .url("https://api.bitget.com/api/v2/mix/market/ticker?symbol=BTCUSDT&productType=USDT-FUTURES")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val data = json.getJSONArray("data").getJSONObject(0)
                    data.getString("baseVolume").toDoubleOrNull() ?: 0.0
                } else { 0.0 }
            }
        } catch (e: Exception) { 0.0 }
    }

    private suspend fun descargarHistorialInicial() = withContext(Dispatchers.IO) {
        agregarLog("📥 Descargando historial...")
        val request = Request.Builder()
            // CAMBIO: Quitamos "_UMCBL"
            .url("https://api.bitget.com/api/v2/mix/market/candles?symbol=BTCUSDT&productType=USDT-FUTURES&granularity=1m&limit=100")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val data = json.getJSONArray("data")
                    for (i in 0 until data.length()) {
                        val candle = data.getJSONArray(i)
                        historialPrecios.add(candle.getString(4).toDouble())
                        historialVolumen.add(candle.getString(5).toDouble())
                    }
                    agregarLog("✅ ${historialPrecios.size} velas cargadas")
                } else {
                    agregarLog("⚠️ Historial HTTP ${response.code}: Verifica Symbol/ProductType")
                }
            }
        } catch (e: Exception) {
            agregarLog("❌ Error Historial: ${e.message}")
        }
    }


    private fun abrirTrade(precio: Double, tipo: String, volatilidad: Double) {
        val factorRiesgo = when {
            volatilidad > 2.5 -> 0.5
            volatilidad > 1.8 -> 0.75
            else -> 1.0
        }
        val tamanoAjustado = tamanoPosicionUSDT * factorRiesgo

        posicionAbierta = true
        tipoPosicion = tipo
        precioEntrada = precio
        maxPnLAlcanzado = 0.0
        ultimaOperacionTime = System.currentTimeMillis()
        totalTrades++
        val icono = if (tipo == "LONG") "📈" else "📉"
        agregarLog("$icono OPEN $tipo @ %.2f".format(precio))
        agregarLog("   └─ Tamaño: $%.2f (${leverage}x) | Vol: %.1f%%".format(tamanoAjustado, volatilidad))
    }

    private fun cerrarTrade(precio: Double, motivo: String) {
        val pnl = calcularPnL(precio)
        val resultadoUSDT = (tamanoPosicionUSDT * leverage) * (pnl / 100)
        currentBalance += resultadoUSDT
        if (resultadoUSDT > 0) tradesGanados++ else tradesPerdidos++
        val winRate = if (totalTrades > 0) (tradesGanados * 100.0 / totalTrades) else 0.0
        val roi = ((currentBalance - saldoInicial) / saldoInicial) * 100
        val emoji = if (resultadoUSDT > 0) "💰" else "📉"
        agregarLog("$emoji CLOSE | $motivo")
        agregarLog("   └─ PnL: %.2f%% | $%.2f | Bal: $%.2f".format(pnl, resultadoUSDT, currentBalance))
        agregarLog("   └─ %dW/%dL (%.0f%% WR) | ROI: %.1f%%".format(tradesGanados, tradesPerdidos, winRate, roi))
        agregarLog("───────────────────────────────────────")
        posicionAbierta = false
        tipoPosicion = ""
    }

    private fun calcularPnL(actual: Double): Double {
        return when (tipoPosicion) {
            "LONG" -> ((actual - precioEntrada) / precioEntrada) * 100 * leverage
            "SHORT" -> ((precioEntrada - actual) / precioEntrada) * 100 * leverage
            else -> 0.0
        }
    }

    private fun actualizarDatos(precio: Double, volumen: Double) {
        historialPrecios.add(precio)
        historialVolumen.add(volumen)
        if (historialPrecios.size > 100) {
            historialPrecios.removeAt(0)
            historialVolumen.removeAt(0)
        }
    }

    private fun agregarLog(mensaje: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val linea = "[$timestamp] $mensaje\n"
        logHistory.insert(0, linea)
        if (logHistory.length > 12000) logHistory.delete(10000, logHistory.length)
        sendBroadcast(Intent("ACTUALIZACION_TRADING").putExtra("LOG_MSG", linea))
    }

    private fun actualizarEstadoUI(mensaje: String) {
        currentStatus = mensaje
        sendBroadcast(Intent("ACTUALIZACION_TRADING").putExtra("STATUS_MSG", mensaje))
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Canal Bot Trading",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(titulo: String, contenido: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(titulo)
            .setContentText(contenido)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        job?.cancel()
        val roi = ((currentBalance - saldoInicial) / saldoInicial) * 100
        val winRate = if (totalTrades > 0) (tradesGanados * 100.0 / totalTrades) else 0.0
        agregarLog("═══════════════════════════════════════")
        agregarLog("🔴 Bot detenido")
        agregarLog("📊 RESUMEN DE SESIÓN:")
        agregarLog("   └─ Inicial: $%.2f → Final: $%.2f".format(saldoInicial, currentBalance))
        agregarLog("   └─ ROI: %.2f%% | Trades: %d (%dW/%dL)".format(roi, totalTrades, tradesGanados, tradesPerdidos))
        agregarLog("   └─ Win Rate: %.1f%%".format(winRate))
        agregarLog("═══════════════════════════════════════")
        super.onDestroy()
    }
}