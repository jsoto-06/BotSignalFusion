// ✅✅✅ TRADING SERVICE V7.2 FINAL (CORREGIDO) ✅✅✅
package com.example.signalfusion

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class TradingService : Service() {
    companion object {
        var isRunning = false
        var currentBalance = 0.0
        var logHistory = StringBuilder()
        var posicionAbierta = false
        var monedaConPosicion = ""
        var tipoPosicion = ""
        var precioEntrada = 0.0
        var lastPrice = "0.0"
        var lastRSI = "--"
        var currentStatus = "Iniciando..."
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID_FOREGROUND = "SignalFusionChannel"
    private val CHANNEL_ID_ALERTS = "SignalFusionAlerts"
    private lateinit var manualCloseReceiver: BroadcastReceiver
    private var isReceiverRegistered = false

    private var targetTP = 6.0
    private var targetSL = 2.6 // ✅ Ajustado a 2.6%
    private var leverage = 10.0
    private var riskPercent = 5.0
    private var activeStrategy = "MODERADA"
    private var candleTimeframe = "15m"
    private var candleIntervalMs = 900_000L
    private var lastCandleTime = 0L
    private var maxPnLAlcanzado = 0.0
    private var tsActivation = 2.5 // ✅ Ajustado a 2.5%
    private var tsCallback = 0.6
    private var initialBalance = 0.0
    private var maxDailyLoss = 10.0
    private var apiKey = ""
    private var apiSecret = ""
    private var apiPassphrase = ""
    private var activeSymbols = mutableListOf<String>()
    private var currentSymbolIndex = 0
    private val historialPreciosMap = mutableMapOf<String, MutableList<Double>>()
    private var ultimaOperacionTime = 0L
    private var consecutiveLosses = 0
    private val baseCooldown = 900_000L
    private var lastHeartbeat = System.currentTimeMillis()
    private val heartbeatInterval = 300_000L
    private var errorCount = 0
    private val ultimateStrategies = mutableMapOf<String, SignalFusionUltimateStrategy>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanalesNotificacion()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "SignalFusion::TradingWakeLock")
        wakeLock?.acquire()

        manualCloseReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "FORZAR_CIERRE_MANUAL") {
                    if (posicionAbierta) {
                        agregarLog("⚠️ PÁNICO: Ejecutando cierre...")
                        CoroutineScope(Dispatchers.IO).launch { cerrarTradeReal("Manual Panic") }
                    } else { actualizarEstadoUI("Limpiando UI...") }
                }
                if (intent?.action == "SOLICITAR_REFRESH_UI" || intent?.action == "SOLICITAR_REFRESH_BALANCE") {
                    CoroutineScope(Dispatchers.IO).launch {
                        obtenerBalanceBitget()?.let { currentBalance = it }
                        actualizarEstadoUI("Sincronizado")
                        cargarHistorialCerradas()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("FORZAR_CIERRE_MANUAL")
            addAction("SOLICITAR_REFRESH_UI")
            addAction("SOLICITAR_REFRESH_BALANCE")
        }
        ContextCompat.registerReceiver(this, manualCloseReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        isReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sincronizarAjustes()
        CoroutineScope(Dispatchers.IO).launch {
            val saldo = obtenerBalanceBitget()
            if (saldo != null && saldo >= 0) {
                currentBalance = saldo
                if (initialBalance == 0.0) initialBalance = currentBalance
                isRunning = true
                agregarLog("✅ MOTOR V7.2 FIXED | Balance: $${"%.2f".format(currentBalance)}")
                // ✅ ERROR DE SINTAXIS ARREGLADO AQUÍ ABAJO:
                agregarLog("📊 TP: ${"%.1f".format(targetTP)}% | SL: ${"%.1f".format(targetSL)}% | R:R ${"%.1f".format(targetTP / targetSL)}:1")
                cargarHistorialCerradas()
                activeSymbols.forEach { setearLeverage(it, "LONG") }
                iniciarCicloTrading()
            } else {
                agregarLog("❌ ERROR: Revisa API Keys")
                stopSelf()
            }
        }
        startForeground(1, createForegroundNotification("SignalFusion V7.2", "Motor activo"))
        return START_STICKY
    }

    private suspend fun cargarHistorialCerradas() = withContext(Dispatchers.IO) {
        try {
            agregarLog("📥 Descargando historial...")

            val endpoint = "/api/v2/mix/order/history-orders?productType=USDT-FUTURES&limit=100"
            val resp = BitgetUtils.authenticatedGet(endpoint, apiKey, apiSecret, apiPassphrase)

            if (resp != null) {
                val json = JSONObject(resp)
                val code = json.optString("code")

                if (code == "00000") {
                    val data = json.optJSONArray("data")

                    if (data != null && data.length() > 0) {

                        var wins = 0
                        var losses = 0
                        var dailyPnl = 0.0
                        val now = System.currentTimeMillis()
                        val unDiaMs = 24L * 60 * 60 * 1000L
                        val ultimas10 = mutableListOf<String>()

                        for (i in 0 until data.length()) {
                            val order = data.getJSONObject(i)

                            var pnl = order.optDouble("profit", 0.0)
                            if (pnl == 0.0) pnl = order.optDouble("realizedPL", 0.0)
                            if (pnl == 0.0) pnl = order.optDouble("netProfit", 0.0)

                            if (pnl != 0.0) {
                                if (pnl > 0) wins++ else losses++

                                val uTime = order.optLong("uTime", order.optLong("cTime", now))
                                if (now - uTime <= unDiaMs) dailyPnl += pnl

                                if (ultimas10.size < 10) {
                                    val symbol = order.optString("symbol", "?").replace("USDT", "")
                                    val side = order.optString("posSide", order.optString("side", "?")).uppercase()
                                    val icono = if (pnl > 0) "🟢" else "🔴"
                                    val signo = if (pnl > 0) "+" else ""
                                    ultimas10.add("$icono $symbol ($side): $signo$${"%.2f".format(pnl)}")
                                }
                            }
                        }

                        val total = wins + losses
                        val wr = if (total > 0) (wins.toDouble() / total) * 100 else 0.0

                        agregarLog("📊 Trades analizados: $total ($wins W / $losses L)")
                        agregarLog("📊 WIN RATE: ${"%.1f".format(wr)}%")
                        agregarLog("💰 PnL 24h: $${"%.2f".format(dailyPnl)}")

                        ultimas10.reversed().forEach { agregarLog(it) }

                        val intent = Intent("ACTUALIZACION_ESTADISTICAS")
                        intent.putExtra("WIN_RATE", "${"%.1f".format(wr)}%")
                        intent.putExtra("DAILY_PNL", dailyPnl)
                        sendBroadcast(intent)

                    } else {
                        agregarLog("📜 No hay historial de trades")
                        val intent = Intent("ACTUALIZACION_ESTADISTICAS")
                        intent.putExtra("WIN_RATE", "0.0%")
                        intent.putExtra("DAILY_PNL", 0.0)
                        sendBroadcast(intent)
                    }
                } else {
                    agregarLog("⚠️ Code error: $code - ${json.optString("msg")}")
                }
            } else {
                agregarLog("❌ Respuesta nula del servidor")
            }
        } catch (e: Exception) {
            agregarLog("⚠️ Error Historial: ${e.message}")
        }
    }

    private fun iniciarCicloTrading() {
        job = CoroutineScope(Dispatchers.IO).launch {
            activeSymbols.forEach { descargarHistorialInicial(it); delay(500) }
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeat > heartbeatInterval) {
                        lastHeartbeat = now
                        agregarLog("💓 Heartbeat | Balance: $${"%.2f".format(currentBalance)}")
                    }
                    if (posicionAbierta) verificarIntegridadPosicion()
                    if (currentBalance > 0 && currentBalance < initialBalance * (1 - (maxDailyLoss / 100.0))) {
                        agregarLog("⛔ CIRCUIT BREAKER"); stopSelf(); break
                    }

                    val symbol = if (posicionAbierta) monedaConPosicion else activeSymbols[currentSymbolIndex]
                    val precio = obtenerPrecioReal(symbol)
                    if (precio > 0) {
                        lastPrice = precio.toString()
                        if (System.currentTimeMillis() - lastCandleTime >= candleIntervalMs) {
                            actualizarDatosVela(symbol, precio)
                            lastCandleTime = System.currentTimeMillis()
                        }
                        val hP = historialPreciosMap[symbol] ?: mutableListOf()
                        if (hP.size >= 50) {
                            if (!posicionAbierta) ejecutarEstrategiaUltimate(symbol, precio, hP)
                            else gestionarSalida(symbol, precio)
                        } else {
                            actualizarEstadoUI("📥 Calibrando $symbol (${hP.size}/50)")
                        }
                    }
                    if (!posicionAbierta) currentSymbolIndex = (currentSymbolIndex + 1) % activeSymbols.size
                    errorCount = 0
                } catch (e: Exception) {
                    agregarLog("⚠️ Loop: ${e.message}")
                    errorCount++
                    if (errorCount > 3) { agregarLog("🚨 Reiniciando..."); delay(5000); stopSelf(); break }
                    else { delay(5000) }
                }
                delay(if (posicionAbierta) 2000 else 3000)
            }
        }
    }

    private fun ejecutarEstrategiaUltimate(symbol: String, precio: Double, hP: List<Double>) {
        if (System.currentTimeMillis() < ultimaOperacionTime) {
            actualizarEstadoUI("⏳ Cooldown: ${(ultimaOperacionTime - System.currentTimeMillis()) / 1000}s")
            return
        }
        val atr = Indicadores.calcularATR(hP, 14)
        val atrPercent = (atr / precio) * 100
        if (atrPercent < 1.2) {
            actualizarEstadoUI("⏸️ ATR Bajo (${"%.2f".format(atrPercent)}%)")
            return
        }

        val rsi = Indicadores.calcularRSI(hP, 14)
        val emas = Indicadores.calcularEMAs(hP)
        val bb = Indicadores.calcularBollingerBands(hP)
        val macd = Indicadores.calcularMACD(hP)
        lastRSI = "%.0f".format(rsi)
        actualizarEstadoUI("Analizando $symbol | RSI: $lastRSI")

        val marketData = MarketData(precio, precio*1.005, precio*0.995, rsi, rsi, emas.fast, emas.slow, emas.mid, emas.trend, bb.first, bb.second, bb.third, macd.first, macd.second, macd.third)
        val cerebro = ultimateStrategies[symbol] ?: return
        val senialFinal = cerebro.evaluate(marketData)

        if ((senialFinal == "LONG" || senialFinal == "SHORT") && !posicionAbierta) {
            posicionAbierta = true
            monedaConPosicion = symbol
            tipoPosicion = senialFinal
            abrirTradeReal(symbol, precio, senialFinal, hP)
        }
    }

    private fun gestionarSalida(symbol: String, precio: Double) {
        val pnlNeto = calcularPnL(precio, true)
        if (pnlNeto > maxPnLAlcanzado) maxPnLAlcanzado = pnlNeto
        actualizarEstadoUI("Operando: ${"%.2f".format(pnlNeto)}%")
        if (maxPnLAlcanzado >= tsActivation && (maxPnLAlcanzado - pnlNeto) >= tsCallback) {
            cerrarTradeReal("Trailing Stop 🛡️")
        }
    }

    private fun abrirTradeReal(symbol: String, precio: Double, tipo: String, hP: List<Double>) {
        CoroutineScope(Dispatchers.IO).launch {
            agregarLog("🎯 SEÑAL DETECTADA: $tipo ($symbol)")
            setearLeverage(symbol, tipo)

            val margenDeseado = when {
                currentBalance < 10.0 -> min(currentBalance * 0.25, 2.5)
                currentBalance < 20.0 -> min(currentBalance * 0.20, 4.0)
                currentBalance < 50.0 -> min(currentBalance * 0.15, 6.0)
                else -> currentBalance * (riskPercent / 100.0)
            }

            val sizeAmount = margenDeseado / precio

            var precisionSize = 1; var precisionPrice = 2
            when {
                symbol.contains("BTC") -> { precisionSize = 3; precisionPrice = 1 }
                symbol.contains("ETH") -> { precisionSize = 2; precisionPrice = 2 }
                symbol.contains("SOL") -> { precisionSize = 1; precisionPrice = 2 }
                symbol.contains("XRP") -> { precisionSize = 0; precisionPrice = 4 }
            }

            val sizeStr = String.format(Locale.US, "%.${precisionSize}f", sizeAmount)
            val sizeFinal = sizeStr.toDoubleOrNull() ?: 0.0

            if (sizeFinal <= 0.0 || currentBalance <= 0.5) {
                agregarLog("⚠️ Saldo insuficiente")
                posicionAbierta = false
                monedaConPosicion = ""
                return@launch
            }

            val distanciaSL = precio * (targetSL / 100.0 / leverage)
            val distanciaTP = precio * (targetTP / 100.0 / leverage)
            val slPrice = if (tipo == "LONG") precio - distanciaSL else precio + distanciaSL
            val tpPrice = if (tipo == "LONG") precio + distanciaTP else precio - distanciaTP

            val json = JSONObject().apply {
                put("symbol", symbol)
                put("productType", "usdt-futures")
                put("marginCoin", "USDT")
                put("marginMode", "crossed")
                put("side", if (tipo == "LONG") "buy" else "sell")
                put("tradeSide", "open")
                put("orderType", "market")
                put("size", sizeStr)
                put("presetStopLossPrice", String.format(Locale.US, "%.${precisionPrice}f", slPrice))
                put("presetTakeProfitPrice", String.format(Locale.US, "%.${precisionPrice}f", tpPrice))
            }

            val resp = BitgetUtils.authenticatedPost("/api/v2/mix/order/place-order", json.toString(), apiKey, apiSecret, apiPassphrase)

            if (resp != null && resp.contains("00000")) {
                delay(1500)
                precioEntrada = precio
                maxPnLAlcanzado = -0.2
                ultimaOperacionTime = System.currentTimeMillis() + baseCooldown
                agregarLog("✅ ORDEN EJECUTADA: Margen $${"%.2f".format(margenDeseado)}")
                actualizarEstadoUI("🚀 POSICIÓN ACTIVA")
                TelegramNotifier.enviarEntrada(applicationContext, "$symbol (REAL 💰)", tipo, precio)
            } else {
                agregarLog("❌ Rechazo Bitget: $resp")
                posicionAbierta = false
                monedaConPosicion = ""
            }
        }
    }

    private suspend fun setearLeverage(symbol: String, tipo: String) = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("symbol", symbol)
                put("productType", "USDT-FUTURES")
                put("marginCoin", "USDT")
                put("leverage", leverage.toInt().toString())
                put("holdSide", if (tipo == "LONG") "long" else "short")
            }
            BitgetUtils.authenticatedPost("/api/v2/mix/account/set-leverage", json.toString(), apiKey, apiSecret, apiPassphrase)
        } catch (e: Exception) {}
    }

    private fun cerrarTradeReal(motivo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            if (!posicionAbierta) return@launch
            agregarLog("📤 CERRANDO ($motivo)")
            val symTemp = monedaConPosicion; val sideTemp = tipoPosicion
            val json = JSONObject().apply {
                put("symbol", monedaConPosicion)
                put("productType", "usdt-futures")
                put("marginCoin", "USDT")
                put("holdSide", if (tipoPosicion == "LONG") "long" else "short")
            }
            val resp = BitgetUtils.authenticatedPost("/api/v2/mix/order/close-positions", json.toString(), apiKey, apiSecret, apiPassphrase)
            if (resp != null && (resp.contains("00000") || resp.contains("success"))) {
                val precioCierre = lastPrice.toDoubleOrNull() ?: precioEntrada
                val pnlFinal = calcularPnL(precioCierre, true)

                if (pnlFinal < 0) {
                    consecutiveLosses++
                    val cooldownMin = 15 + (consecutiveLosses * 10)
                    ultimaOperacionTime = System.currentTimeMillis() + (cooldownMin * 60_000L)
                    agregarLog("⏸️ Cooldown ${cooldownMin}min tras pérdidas")
                } else {
                    consecutiveLosses = 0
                    ultimaOperacionTime = System.currentTimeMillis() + baseCooldown
                }

                posicionAbierta = false
                monedaConPosicion = ""
                maxPnLAlcanzado = 0.0
                obtenerBalanceBitget()?.let { currentBalance = it }
                agregarLog("🏁 CERRADO: PnL ${"%.2f".format(pnlFinal)}%")
                actualizarEstadoUI("Escaneando...")
                TelegramNotifier.enviarSalida(applicationContext, symTemp, sideTemp, pnlFinal, precioCierre)
                cargarHistorialCerradas()
            } else {
                agregarLog("❌ ERROR CIERRE: $resp")
            }
        }
    }

    private fun calcularPnL(actual: Double, conFees: Boolean): Double {
        if (precioEntrada <= 0) return 0.0
        val diff = if (tipoPosicion == "LONG") actual - precioEntrada else precioEntrada - actual
        val bruto = (diff / precioEntrada) * 100 * leverage
        return if (conFees) bruto - (0.12 * leverage) else bruto
    }

    private fun actualizarDatosVela(symbol: String, precio: Double) {
        val lista = historialPreciosMap[symbol] ?: return
        lista.add(precio); if (lista.size > 250) lista.removeAt(0)
    }

    private suspend fun descargarHistorialInicial(s: String) = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.bitget.com/api/v2/mix/market/candles?symbol=$s&productType=USDT-FUTURES&granularity=$candleTimeframe&limit=200"
            val body = client.newCall(Request.Builder().url(url).build()).execute().body?.string()
            val data = JSONObject(body ?: "{}").getJSONArray("data")
            val precios = mutableListOf<Double>()
            for (i in data.length() - 1 downTo 0) { precios.add(data.getJSONArray(i).getString(4).toDouble()) }
            historialPreciosMap[s] = precios
            agregarLog("✅ $s: ${precios.size} velas")
        } catch (e: Exception) {}
    }

    private suspend fun verificarIntegridadPosicion() {
        try {
            val endpoint = "/api/v2/mix/position/single-position?symbol=$monedaConPosicion&productType=usdt-futures&marginCoin=USDT"
            val resp = BitgetUtils.authenticatedGet(endpoint, apiKey, apiSecret, apiPassphrase)
            if (resp != null) {
                val json = JSONObject(resp)
                if (json.optString("code") == "00000") {
                    val data = json.optJSONArray("data")
                    var isRealmenteAbierta = false

                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val total = data.getJSONObject(i).optString("total", "0").toDoubleOrNull() ?: 0.0
                            if (total > 0.0) { isRealmenteAbierta = true; break }
                        }
                    }

                    if (!isRealmenteAbierta && posicionAbierta) {
                        agregarLog("⚠️ Cerrada externamente (TP/SL tocado)")
                        posicionAbierta = false
                        monedaConPosicion = ""
                        cargarHistorialCerradas()
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private fun actualizarEstadoUI(mensaje: String) {
        val i = Intent("ACTUALIZACION_TRADING")
        i.putExtra("STATUS_MSG", mensaje)
        i.putExtra("BALANCE", currentBalance)
        i.putExtra("IS_TRADE_OPEN", posicionAbierta)
        if (posicionAbierta) {
            val pnl = calcularPnL(lastPrice.toDoubleOrNull() ?: precioEntrada, true)
            i.putExtra("TRADE_SYMBOL", monedaConPosicion)
            i.putExtra("TRADE_TYPE", tipoPosicion)
            i.putExtra("TRADE_PNL", pnl)
        }
        sendBroadcast(i)
    }

    private suspend fun obtenerBalanceBitget(): Double? = withContext(Dispatchers.IO) {
        try {
            val resp = BitgetUtils.authenticatedGet("/api/v2/mix/account/accounts?productType=USDT-FUTURES", apiKey, apiSecret, apiPassphrase)
            val bal = JSONObject(resp ?: "{}").getJSONArray("data").getJSONObject(0).getString("available").toDouble()
            getSharedPreferences("BotConfig", Context.MODE_PRIVATE).edit().putString("LAST_KNOWN_BALANCE", bal.toString()).apply()
            bal
        } catch (e: Exception) { null }
    }

    private suspend fun obtenerPrecioReal(s: String): Double = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.bitget.com/api/v2/mix/market/ticker?symbol=$s&productType=USDT-FUTURES"
            val body = client.newCall(Request.Builder().url(url).build()).execute().body?.string()
            JSONObject(body ?: "{}").getJSONArray("data").getJSONObject(0).getString("lastPr").toDouble()
        } catch (e: Exception) { 0.0 }
    }

    private fun sincronizarAjustes() {
        val p = getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        apiKey = p.getString("API_KEY", "") ?: ""
        apiSecret = p.getString("SECRET_KEY", "") ?: ""
        apiPassphrase = p.getString("API_PASSPHRASE", "") ?: ""

        leverage = 10.0
        riskPercent = 5.0
        targetTP = 6.0
        targetSL = 2.6
        candleTimeframe = "15m"
        activeStrategy = p.getString("STRATEGY", "MODERADA") ?: "MODERADA"
        candleIntervalMs = 900_000L

        activeSymbols.clear()
        if (p.getBoolean("COIN_BTC", true)) activeSymbols.add("BTCUSDT")
        if (p.getBoolean("COIN_ETH", false)) activeSymbols.add("ETHUSDT")
        if (p.getBoolean("COIN_SOL", false)) activeSymbols.add("SOLUSDT")
        if (p.getBoolean("COIN_XRP", false)) activeSymbols.add("XRPUSDT")
        if (activeSymbols.isEmpty()) activeSymbols.add("BTCUSDT")

        ultimateStrategies.clear()
        activeSymbols.forEach { symbol -> ultimateStrategies[symbol] = SignalFusionUltimateStrategy(candleTimeframe, activeStrategy) }
    }

    private fun agregarLog(m: String) {
        logHistory.insert(0, "[${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}] $m\n")
        sendBroadcast(Intent("ACTUALIZACION_TRADING").putExtra("LOG_MSG", m))
    }

    private fun crearCanalesNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val m = getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(NotificationChannel(CHANNEL_ID_FOREGROUND, "Motor", NotificationManager.IMPORTANCE_LOW))
            m.createNotificationChannel(NotificationChannel(CHANNEL_ID_ALERTS, "Alertas", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun createForegroundNotification(t: String, c: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle(t).setContentText(c).setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true).setPriority(NotificationCompat.PRIORITY_MAX).setContentIntent(pendingIntent).build()
    }

    override fun onDestroy() {
        isRunning = false; job?.cancel(); try { wakeLock?.release() } catch (e: Exception) {}
        if (isReceiverRegistered) unregisterReceiver(manualCloseReceiver)
        super.onDestroy()
    }
}