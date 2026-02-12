package com.example.signalfusion

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

        // Memoria Caché UI
        var lastRSI = "--"
        var lastVol = "--"
        var lastTrend = "--"
        var currentStatus = "Iniciando..."

        var totalTrades = 0
        var tradesGanados = 0
        var tradesPerdidos = 0
    }

    private val client = OkHttpClient()
    private var job: Job? = null
    private val CHANNEL_ID = "BitgetBotChannel"

    // Configuración
    private var targetTP = 2.0
    private var targetSL = 1.5
    private var leverage = 5.0
    private var isTurbo = false
    private var tamanoPosicionUSDT = 0.0

    // NUEVO: Variable de Estrategia Activa
    private var activeStrategy = "MODERADA"

    // API Keys
    private var apiKey = ""
    private var apiSecret = ""
    private var passphrase = ""

    // Datos Mercado
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

            // Notificación dinámica según estrategia
            val notifText = "Estrategia: $activeStrategy ${obtenerIconoEstrategia()}"

            val notificacion = createNotification("SignalFusion Pro", notifText)
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
        apiKey = prefs.getString("API_KEY", "") ?: ""
        apiSecret = prefs.getString("SECRET_KEY", "") ?: ""
        targetTP = prefs.getString("TP_VAL", "2.0")?.toDoubleOrNull() ?: 2.0
        targetSL = prefs.getString("SL_VAL", "1.5")?.toDoubleOrNull() ?: 1.5
        leverage = prefs.getInt("LEVERAGE", 5).toDouble()
        isTurbo = prefs.getBoolean("TURBO_MODE", false)
        tamanoPosicionUSDT = currentBalance * 0.02

        // Cargar Estrategia
        activeStrategy = prefs.getString("STRATEGY", "MODERADA") ?: "MODERADA"
    }

    private fun iniciarCicloTrading() {
        job = CoroutineScope(Dispatchers.IO).launch {
            agregarLog("🚀 SignalFusion Pro v3.0 Iniciado")
            agregarLog("🧠 Estrategia Activa: $activeStrategy ${obtenerIconoEstrategia()}")
            agregarLog("💰 Capital: $%.2f | Leverage: ${leverage}x".format(currentBalance))

            descargarHistorialInicial()

            while (isActive) {
                try {
                    val precioActual = obtenerPrecioReal()
                    val volumenActual = obtenerVolumenReal()

                    if (precioActual > 0) {
                        actualizarDatos(precioActual, volumenActual)

                        if (historialPrecios.size >= 50) {
                            // AQUÍ ESTÁ LA MAGIA: Seleccionamos la lógica según la estrategia
                            when (activeStrategy) {
                                "AGRESIVA" -> ejecutarEstrategiaAgresiva(precioActual, volumenActual)
                                "BREAKOUT" -> ejecutarEstrategiaBreakout(precioActual, volumenActual)
                                else -> ejecutarEstrategiaModerada(precioActual, volumenActual)
                            }
                        } else {
                            val progreso = (historialPrecios.size * 100) / 50
                            actualizarEstadoUI("📥 Cargando datos: ${historialPrecios.size}/50 (${progreso}%%)")
                        }
                    } else {
                        actualizarEstadoUI("⚠️ Error precio. Reintentando...")
                    }

                } catch (e: Exception) {
                    agregarLog("❌ Error en ciclo: ${e.message}")
                }

                // Delay dinámico según la estrategia
                val delayTime = when {
                    activeStrategy == "AGRESIVA" && isTurbo -> 2000L
                    activeStrategy == "AGRESIVA" -> 5000L
                    activeStrategy == "BREAKOUT" -> 8000L // Breakout necesita calma
                    isTurbo -> 3000L
                    else -> 6000L
                }
                delay(delayTime)
            }
        }
    }

    // ==========================================
    // 🟢 ESTRATEGIA 1: MODERADA (Balanceada)
    // ==========================================
    private fun ejecutarEstrategiaModerada(precio: Double, volumen: Double) {
        val rsi = Indicadores.calcularRSI(historialPrecios, 14)
        val ema9 = Indicadores.calcularEMA(historialPrecios, 9)
        val ema21 = Indicadores.calcularEMA(historialPrecios, 21)
        val atr = Indicadores.calcularATR(historialPrecios, 14)
        val volPromedio = historialVolumen.takeLast(20).average()
        val volatilidad = (atr / precio) * 100

        actualizarMemoriaCache(rsi, volatilidad, ema9 > ema21, precio)

        if (!posicionAbierta) {
            val cooldownOk = System.currentTimeMillis() - ultimaOperacionTime > 60000
            val volFuerte = volumen > volPromedio * 1.1
            val volumenOk = volPromedio > 0 && volumen > 0
            val volatilNoExtrema = volatilidad < 3.5

            if (!cooldownOk) { actualizarEstadoUI("⏳ Cooldown activo..."); return }

            val rsiBajo = if (isTurbo) rsi < 40 else rsi < 35
            val rsiAlto = if (isTurbo) rsi > 60 else rsi > 65

            // Rango de precio un poco más flexible (0.7%)
            val precioEnSoporte = precio > ema9 * 0.993 && precio < ema9 * 1.007
            val precioEnResistencia = precio < ema9 * 1.007 && precio > ema9 * 0.993

            val señalLong = cooldownOk && volumenOk && volFuerte && rsiBajo && precioEnSoporte && (ema9 > ema21) && volatilNoExtrema
            val señalShort = cooldownOk && volumenOk && volFuerte && rsiAlto && precioEnResistencia && (ema9 < ema21) && volatilNoExtrema

            if (señalLong) abrirTrade(precio, "LONG", volatilidad)
            else if (señalShort) abrirTrade(precio, "SHORT", volatilidad)
            else actualizarEstadoUI("🔍 MODERADA | RSI:%.0f | Vol:%.1f%%".format(rsi, volatilidad))

        } else {
            gestionarSalida(precio, rsi, ema9, ema21, volatilidad, 30.0, 70.0)
        }
    }

    // ==========================================
    // 🔴 ESTRATEGIA 2: AGRESIVA (Alta Frecuencia)
    // ==========================================
    private fun ejecutarEstrategiaAgresiva(precio: Double, volumen: Double) {
        val rsi = Indicadores.calcularRSI(historialPrecios, 14)
        val ema9 = Indicadores.calcularEMA(historialPrecios, 9)
        val ema21 = Indicadores.calcularEMA(historialPrecios, 21)
        val atr = Indicadores.calcularATR(historialPrecios, 14)
        val volPromedio = historialVolumen.takeLast(20).average()
        val volatilidad = (atr / precio) * 100

        actualizarMemoriaCache(rsi, volatilidad, ema9 > ema21, precio)

        if (!posicionAbierta) {
            // Cooldown reducido a 45s
            val cooldownOk = System.currentTimeMillis() - ultimaOperacionTime > 45000
            // Volumen más sensible (solo 5% extra)
            val volFuerte = volumen > volPromedio * 1.05
            val volumenOk = volPromedio > 0 && volumen > 0
            // Tolera más volatilidad (hasta 5%)
            val volatilNoExtrema = volatilidad < 5.0

            if (!cooldownOk) { actualizarEstadoUI("⏳ Cooldown rápido..."); return }

            // RSI mucho más permisivo
            val rsiBajo = if (isTurbo) rsi < 45 else rsi < 40
            val rsiAlto = if (isTurbo) rsi > 55 else rsi > 60

            // Rango de precio muy amplio (1%)
            val precioEnSoporte = precio > ema9 * 0.990 && precio < ema9 * 1.010
            val precioEnResistencia = precio < ema9 * 1.010 && precio > ema9 * 0.990

            val señalLong = cooldownOk && volumenOk && volFuerte && rsiBajo && precioEnSoporte && (ema9 > ema21) && volatilNoExtrema
            val señalShort = cooldownOk && volumenOk && volFuerte && rsiAlto && precioEnResistencia && (ema9 < ema21) && volatilNoExtrema

            if (señalLong) abrirTrade(precio, "LONG", volatilidad)
            else if (señalShort) abrirTrade(precio, "SHORT", volatilidad)
            else actualizarEstadoUI("🔥 AGRESIVA | RSI:%.0f | Vol:%.1f%%".format(rsi, volatilidad))

        } else {
            // Salidas más rápidas
            gestionarSalida(precio, rsi, ema9, ema21, volatilidad, 32.0, 68.0)
        }
    }

    // ==========================================
    // 🔵 ESTRATEGIA 3: BREAKOUT (Francotirador)
    // ==========================================
    private fun ejecutarEstrategiaBreakout(precio: Double, volumen: Double) {
        val rsi = Indicadores.calcularRSI(historialPrecios, 14)
        val ema9 = Indicadores.calcularEMA(historialPrecios, 9)
        val ema21 = Indicadores.calcularEMA(historialPrecios, 21)
        val ema50 = Indicadores.calcularEMA(historialPrecios, 50) // Confirmación extra
        val atr = Indicadores.calcularATR(historialPrecios, 14)
        val volPromedio = historialVolumen.takeLast(20).average()
        val volatilidad = (atr / precio) * 100

        actualizarMemoriaCache(rsi, volatilidad, ema9 > ema21, precio)

        if (!posicionAbierta) {
            // Cooldown largo (2 minutos), busca calidad
            val cooldownOk = System.currentTimeMillis() - ultimaOperacionTime > 120000
            // Volumen EXTREMO necesario (50% más del promedio)
            val volExtremo = volumen > volPromedio * 1.5
            val volumenOk = volPromedio > 0 && volumen > 0

            if (!cooldownOk) { actualizarEstadoUI("⏳ Esperando setup perfecto..."); return }

            // Lógica de Breakout Alcista
            val rsiRecuperando = rsi > 30 && rsi < 55 // Saliendo del suelo con fuerza
            val tendenciaFuerteUp = ema9 > ema21 && ema21 > ema50 // Triple confirmación
            val precioRompiendoUp = precio > ema9 // Precio saltando la media rápida

            // Lógica de Breakout Bajista
            val rsiCayendo = rsi < 70 && rsi > 45
            val tendenciaFuerteDown = ema9 < ema21 && ema21 < ema50
            val precioRompiendoDown = precio < ema9

            val señalLong = cooldownOk && volumenOk && volExtremo && rsiRecuperando && tendenciaFuerteUp && precioRompiendoUp
            val señalShort = cooldownOk && volumenOk && volExtremo && rsiCayendo && tendenciaFuerteDown && precioRompiendoDown

            if (señalLong) abrirTrade(precio, "LONG", volatilidad)
            else if (señalShort) abrirTrade(precio, "SHORT", volatilidad)
            else actualizarEstadoUI("🎯 BREAKOUT | RSI:%.0f | Vol:%.1f%%".format(rsi, volatilidad))

        } else {
            gestionarSalida(precio, rsi, ema9, ema21, volatilidad, 25.0, 75.0)
        }
    }

    // ==========================================
    // LÓGICA COMÚN DE GESTIÓN Y CIERRE
    // ==========================================
    private fun gestionarSalida(precio: Double, rsi: Double, ema9: Double, ema21: Double, volatilidad: Double, rsiMin: Double, rsiMax: Double) {
        val pnl = calcularPnL(precio)
        if (pnl > maxPnLAlcanzado) maxPnLAlcanzado = pnl

        val emoji = if (pnl > 0) "📈" else "🔴"
        actualizarEstadoUI("$emoji $tipoPosicion %.2f | PnL:%.2f%%".format(precioEntrada, pnl))

        // Take Profit y Stop Loss Base
        if (pnl >= targetTP) cerrarTrade(precio, "✅ Take Profit")
        else if (pnl <= -targetSL) cerrarTrade(precio, "🛑 Stop Loss")

        // Trailing Stop según estrategia
        val umbralTrailing = if (activeStrategy == "AGRESIVA") 0.8 else 1.0
        val retroceso = if (activeStrategy == "AGRESIVA") 0.4 else 0.5

        if (maxPnLAlcanzado >= umbralTrailing && (maxPnLAlcanzado - pnl) >= retroceso) {
            cerrarTrade(precio, "🛡️ Trailing Stop")
        }

        // Cierre por Indicadores (Giro de tendencia)
        if (tipoPosicion == "LONG" && rsi > rsiMax && ema9 < ema21) cerrarTrade(precio, "🔄 Giro bajista")
        if (tipoPosicion == "SHORT" && rsi < rsiMin && ema9 > ema21) cerrarTrade(precio, "🔄 Giro alcista")

        // Cierre por Volatilidad Extrema
        if (volatilidad > 6.0) cerrarTrade(precio, "⚡ Volatilidad Peligrosa")
    }

    // ==========================================
    // UTILIDADES INTERNAS
    // ==========================================

    private fun actualizarMemoriaCache(rsi: Double, vol: Double, trendUp: Boolean, precio: Double) {
        lastRSI = "%.0f".format(rsi)
        lastVol = "%.1f%%".format(vol)
        lastTrend = if (trendUp) "LONG ↗" else "SHORT ↘"
        lastPrice = "%.2f".format(precio)
    }

    private fun obtenerIconoEstrategia(): String {
        return when (activeStrategy) {
            "AGRESIVA" -> "🔴"
            "BREAKOUT" -> "🎯"
            else -> "🟢"
        }
    }

    private fun abrirTrade(precio: Double, tipo: String, volatilidad: Double) {
        val factorRiesgo = if (volatilidad > 3.0) 0.5 else 1.0
        val tamanoAjustado = tamanoPosicionUSDT * factorRiesgo

        posicionAbierta = true
        tipoPosicion = tipo
        precioEntrada = precio
        maxPnLAlcanzado = 0.0
        ultimaOperacionTime = System.currentTimeMillis()
        totalTrades++

        agregarLog("OPEN $tipo @ %.2f".format(precio))
        // AQUÍ VA LA LLAMADA A BITGET REAL SI USARAS EL UTILS
        // enviarOrdenReal(tipo, tamanoAjustado)
    }

    private fun cerrarTrade(precio: Double, motivo: String) {
        val pnl = calcularPnL(precio)
        val resultadoUSDT = (tamanoPosicionUSDT * leverage) * (pnl / 100)
        currentBalance += resultadoUSDT
        if (resultadoUSDT > 0) tradesGanados++ else tradesPerdidos++

        agregarLog("CLOSE | $motivo | PnL: %.2f%%".format(pnl))
        posicionAbierta = false
        tipoPosicion = ""
        // enviarOrdenReal("CLOSE", tamanoPosicionUSDT)
    }

    private fun calcularPnL(actual: Double): Double {
        return when (tipoPosicion) {
            "LONG" -> ((actual - precioEntrada) / precioEntrada) * 100 * leverage
            "SHORT" -> ((precioEntrada - actual) / precioEntrada) * 100 * leverage
            else -> 0.0
        }
    }

    // --- FUNCIONES DE RED (SIMPLIFICADAS PARA AHORRAR ESPACIO) ---
    private suspend fun obtenerPrecioReal(): Double = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://api.bitget.com/api/v2/mix/market/ticker?symbol=BTCUSDT&productType=USDT-FUTURES").build()
        try { client.newCall(request).execute().use { response ->
            if (response.isSuccessful) JSONObject(response.body?.string() ?: "{}").getJSONArray("data").getJSONObject(0).getString("lastPr").toDouble() else 0.0
        }} catch (e: Exception) { 0.0 }
    }

    private suspend fun obtenerVolumenReal(): Double = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://api.bitget.com/api/v2/mix/market/ticker?symbol=BTCUSDT&productType=USDT-FUTURES").build()
        try { client.newCall(request).execute().use { response ->
            if (response.isSuccessful) JSONObject(response.body?.string() ?: "{}").getJSONArray("data").getJSONObject(0).getString("baseVolume").toDoubleOrNull() ?: 0.0 else 0.0
        }} catch (e: Exception) { 0.0 }
    }

    private suspend fun descargarHistorialInicial() = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://api.bitget.com/api/v2/mix/market/candles?symbol=BTCUSDT&productType=USDT-FUTURES&granularity=1m&limit=100").build()
        try { client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val data = JSONObject(response.body?.string() ?: "{}").getJSONArray("data")
                for (i in 0 until data.length()) {
                    val c = data.getJSONArray(i)
                    historialPrecios.add(c.getString(4).toDouble())
                    historialVolumen.add(c.getString(5).toDouble())
                }
                agregarLog("✅ Historial cargado (${activeStrategy})")
            }
        }} catch (e: Exception) { agregarLog("❌ Error: ${e.message}") }
    }

    private fun actualizarDatos(precio: Double, volumen: Double) {
        historialPrecios.add(precio); historialVolumen.add(volumen)
        if (historialPrecios.size > 100) { historialPrecios.removeAt(0); historialVolumen.removeAt(0) }
    }

    private fun agregarLog(mensaje: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val linea = "[$timestamp] $mensaje\n"
        logHistory.insert(0, linea)
        sendBroadcast(Intent("ACTUALIZACION_TRADING").putExtra("LOG_MSG", linea))
    }

    private fun actualizarEstadoUI(mensaje: String) {
        currentStatus = mensaje
        sendBroadcast(Intent("ACTUALIZACION_TRADING").putExtra("STATUS_MSG", mensaje))
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Bot Trading", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(titulo: String, contenido: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(titulo).setContentText(contenido)
            .setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true).build()
    }

    override fun onDestroy() {
        isRunning = false; job?.cancel()
        super.onDestroy()
    }
}