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

class TradingService : Service() {

    companion object {
        var isRunning = false
        var currentBalance = 0.0
        var logHistory = StringBuilder()

        // 🟢 ESTADO GLOBAL
        var posicionAbierta = false
        var monedaConPosicion = ""
        var tipoPosicion = ""
        var precioEntrada = 0.0

        // Caché UI
        var lastPrice = "0.0"
        var lastRSI = "--"
        var currentStatus = "Iniciando..."

        // 📊 ESTADÍSTICAS
        var totalTrades = 0
        var tradesGanados = 0
        var tradesPerdidos = 0
    }

    private val client = OkHttpClient()
    private var job: Job? = null
    private val CHANNEL_ID_FOREGROUND = "SignalFusionChannel"
    private val CHANNEL_ID_ALERTS = "SignalFusionAlerts"

    private lateinit var manualCloseReceiver: BroadcastReceiver
    private var isReceiverRegistered = false

    // --- CONFIGURACIÓN DE RIESGO ---
    private var targetTP = 2.15
    private var targetSL = 1.65
    private var leverage = 5.0
    private var riskPercent = 20.0
    private var activeStrategy = "AGRESIVA"

    // Control de Velas
    private var lastCandleTime = 0L
    private val CANDLE_INTERVAL = 60_000L

    // Trailing Stop
    private var maxPnLAlcanzado = 0.0
    private var tsActivation = 1.5
    private var tsCallback = 0.5

    // Circuit Breaker
    private var initialBalance = 0.0
    private var maxDailyLoss = 10.0
    private var consecutiveLosses = 0

    // Credenciales
    private var apiKey = ""
    private var apiSecret = ""
    private var apiPassphrase = ""

    private var activeSymbols = mutableListOf<String>()
    private var currentSymbolIndex = 0
    private val historialPreciosMap = mutableMapOf<String, MutableList<Double>>()
    private var ultimaOperacionTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanalesNotificacion()

        manualCloseReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "FORZAR_CIERRE_MANUAL" && posicionAbierta) {
                    agregarLog("⚠️ BOTÓN PÁNICO: Forzando cierre...")
                    CoroutineScope(Dispatchers.IO).launch { cerrarTradeReal("Manual Panic") }
                }
                if (intent?.action == "SOLICITAR_REFRESH_UI") {
                    actualizarEstadoUI("Sincronizado")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("FORZAR_CIERRE_MANUAL")
            addAction("SOLICITAR_REFRESH_UI")
        }
        ContextCompat.registerReceiver(this, manualCloseReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        isReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sincronizarAjustes()
        CoroutineScope(Dispatchers.IO).launch {
            val saldo = obtenerBalanceBitget()
            if (saldo != null && saldo > 0) {
                currentBalance = saldo
                if (initialBalance == 0.0) initialBalance = currentBalance
                isRunning = true
                agregarLog("✅ MOTOR V4.2 ONLINE | Saldo: $${"%.2f".format(currentBalance)}")
                iniciarCicloTrading()
            } else {
                agregarLog("❌ ERROR: Verifica API Keys y Saldo.")
                stopSelf()
            }
        }
        startForeground(1, createForegroundNotification("SignalFusion Pro", "Protección Activa"))
        return START_STICKY
    }

    private fun iniciarCicloTrading() {
        job = CoroutineScope(Dispatchers.IO).launch {
            activeSymbols.forEach { descargarHistorialInicial(it); delay(500) }

            while (isActive) {
                try {
                    // Circuit Breaker
                    if (currentBalance < initialBalance * (1 - (maxDailyLoss / 100.0))) {
                        agregarLog("⛔ CIRCUIT BREAKER: Pérdida máxima. Apagando.")
                        stopSelf()
                        break
                    }

                    val symbol = if (posicionAbierta) monedaConPosicion else activeSymbols[currentSymbolIndex]
                    val precio = obtenerPrecioReal(symbol)

                    if (precio > 0) {
                        lastPrice = precio.toString()

                        if (System.currentTimeMillis() - lastCandleTime >= CANDLE_INTERVAL) {
                            actualizarDatosVela(symbol, precio)
                            lastCandleTime = System.currentTimeMillis()
                        }

                        val hP = historialPreciosMap[symbol] ?: mutableListOf()

                        if (hP.size >= 50) {
                            if (!posicionAbierta) {
                                ejecutarEstrategia(symbol, precio, hP)
                            } else {
                                gestionarSalida(symbol, precio)
                            }
                        } else {
                            actualizarEstadoUI("📥 Calibrando $symbol (${hP.size}/50)")
                        }
                    }
                    if (!posicionAbierta) currentSymbolIndex = (currentSymbolIndex + 1) % activeSymbols.size

                } catch (e: Exception) {
                    agregarLog("⚠️ Loop: ${e.message}")
                }
                delay(if (posicionAbierta) 2000 else 3000)
            }
        }
    }

    private fun ejecutarEstrategia(symbol: String, precio: Double, hP: List<Double>) {
        val rsi = Indicadores.calcularRSI(hP, 14)
        val ema9 = Indicadores.calcularEMA(hP, 9)
        val ema21 = Indicadores.calcularEMA(hP, 21)

        lastRSI = "%.0f".format(rsi)
        actualizarEstadoUI("Escaneando $symbol | RSI: $lastRSI")

        if (System.currentTimeMillis() - ultimaOperacionTime < 60000) return

        if (activeStrategy == "AGRESIVA") {
            if (rsi < 45 && ema9 > ema21) abrirTradeReal(symbol, precio, "LONG")
            else if (rsi > 55 && ema9 < ema21) abrirTradeReal(symbol, precio, "SHORT")
        } else {
            if (rsi < 35 && ema9 > ema21) abrirTradeReal(symbol, precio, "LONG")
            else if (rsi > 65 && ema9 < ema21) abrirTradeReal(symbol, precio, "SHORT")
        }
    }

    private fun gestionarSalida(symbol: String, precio: Double) {
        val pnlNeto = calcularPnL(precio, true)
        if (pnlNeto > maxPnLAlcanzado) maxPnLAlcanzado = pnlNeto

        actualizarEstadoUI("Operando: ${"%.2f".format(pnlNeto)}%")

        if (pnlNeto >= targetTP) cerrarTradeReal("Take Profit ✅")
        else if (pnlNeto <= -targetSL) cerrarTradeReal("Stop Loss 🛑")
        else if (maxPnLAlcanzado >= tsActivation && (maxPnLAlcanzado - pnlNeto) >= tsCallback) {
            cerrarTradeReal("Trailing Stop 🛡️")
        }
    }

    private fun abrirTradeReal(symbol: String, precio: Double, tipo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            // 1. Apalancamiento
            val levJson = JSONObject()
            levJson.put("symbol", symbol)
            levJson.put("productType", "usdt-futures")
            levJson.put("marginCoin", "USDT")
            levJson.put("leverage", leverage.toInt().toString())
            levJson.put("holdSide", if (tipo == "LONG") "long" else "short")

            BitgetUtils.authenticatedPost("/api/v2/mix/account/set-leverage", levJson.toString(), apiKey, apiSecret, apiPassphrase)

            // 2. Cálculo de Tamaño
            val margenDeseado = currentBalance * (riskPercent / 100.0)
            val sizeAmount = (margenDeseado * leverage) / precio

            var precisionSize = 1
            if (symbol.contains("BTC")) precisionSize = 3
            else if (symbol.contains("ETH")) precisionSize = 2
            else if (symbol.contains("SOL")) precisionSize = 1
            else if (symbol.contains("XRP")) precisionSize = 0

            val sizeStr = String.format(Locale.US, "%.${precisionSize}f", sizeAmount)

            if (sizeStr.toDouble() <= 0.0) {
                agregarLog("⚠️ Capital insuficiente")
                return@launch
            }

            // 3. Cálculo de SL/TP para el Exchange (Sin líneas largas)
            var stopLossPrice = 0.0
            var takeProfitPrice = 0.0
            val riskRatio = targetSL / 100.0 / leverage
            val rewardRatio = targetTP / 100.0 / leverage

            if (tipo == "LONG") {
                stopLossPrice = precio * (1.0 - riskRatio)
                takeProfitPrice = precio * (1.0 + rewardRatio)
            } else {
                stopLossPrice = precio * (1.0 + riskRatio)
                takeProfitPrice = precio * (1.0 - rewardRatio)
            }

            var precisionPrice = 2
            if (symbol.contains("BTC")) precisionPrice = 1
            else if (symbol.contains("XRP")) precisionPrice = 4

            val slStr = String.format(Locale.US, "%.${precisionPrice}f", stopLossPrice)
            val tpStr = String.format(Locale.US, "%.${precisionPrice}f", takeProfitPrice)

            agregarLog("📤 ORDEN: $tipo $sizeStr $symbol | SL: $slStr")

            // 4. Envío de Orden
            val ordenJson = JSONObject()
            ordenJson.put("symbol", symbol)
            ordenJson.put("productType", "usdt-futures")
            ordenJson.put("marginCoin", "USDT")
            ordenJson.put("marginMode", "crossed")
            ordenJson.put("side", if (tipo == "LONG") "buy" else "sell")
            ordenJson.put("tradeSide", "open")
            ordenJson.put("orderType", "market")
            ordenJson.put("size", sizeStr)
            ordenJson.put("presetStopLossPrice", slStr)
            ordenJson.put("presetTakeProfitPrice", tpStr)

            val resp = BitgetUtils.authenticatedPost("/api/v2/mix/order/place-order", ordenJson.toString(), apiKey, apiSecret, apiPassphrase)

            if (resp != null) {
                val json = JSONObject(resp)
                if (json.optString("code") == "00000") {
                    posicionAbierta = true
                    monedaConPosicion = symbol
                    tipoPosicion = tipo
                    precioEntrada = precio
                    maxPnLAlcanzado = -0.2
                    ultimaOperacionTime = System.currentTimeMillis()
                    totalTrades++

                    agregarLog("✅ EJECUTADO: $symbol")
                    actualizarEstadoUI("🚀 POSICIÓN ACTIVA")
                    enviarAlertaPush("NUEVA POSICIÓN 🚀", "$tipo $symbol", false)
                } else {
                    agregarLog("❌ RECHAZADO: ${json.optString("msg")}")
                }
            } else {
                agregarLog("❌ ERROR DE RED")
            }
        }
    }

    private fun cerrarTradeReal(motivo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            agregarLog("📤 CERRANDO ($motivo)...")

            val jsonBody = JSONObject()
            jsonBody.put("symbol", monedaConPosicion)
            jsonBody.put("productType", "usdt-futures")
            jsonBody.put("marginCoin", "USDT")
            jsonBody.put("holdSide", if (tipoPosicion == "LONG") "long" else "short")

            val resp = BitgetUtils.authenticatedPost("/api/v2/mix/order/close-positions", jsonBody.toString(), apiKey, apiSecret, apiPassphrase)

            if (resp != null && (resp.contains("00000") || resp.contains("success"))) {
                val pnlFinal = calcularPnL(lastPrice.toDoubleOrNull() ?: precioEntrada, true)
                if (pnlFinal > 0) {
                    tradesGanados++
                    consecutiveLosses = 0
                } else {
                    tradesPerdidos++
                    consecutiveLosses++
                }

                val oldSymbol = monedaConPosicion
                posicionAbierta = false
                monedaConPosicion = ""
                maxPnLAlcanzado = 0.0

                obtenerBalanceBitget()?.let { currentBalance = it }
                agregarLog("🏁 CERRADO: $oldSymbol")
                actualizarEstadoUI("Buscando entradas...")
                enviarAlertaPush("CERRADO 💰", "PnL: ${"%.2f".format(pnlFinal)}%", pnlFinal > 0)
            } else {
                agregarLog("❌ FALLO CIERRE: $resp")
            }
        }
    }

    private fun calcularPnL(actual: Double, conFees: Boolean): Double {
        if (precioEntrada <= 0) return 0.0
        val diff = if (tipoPosicion == "LONG") actual - precioEntrada else precioEntrada - actual
        val bruto = (diff / precioEntrada) * 100 * leverage
        return if (conFees) bruto - (0.12 * leverage) else bruto
    }

    // --- DATOS Y HERRAMIENTAS ---
    private fun actualizarDatosVela(symbol: String, precio: Double) {
        val lista = historialPreciosMap[symbol] ?: return
        lista.add(precio)
        if (lista.size > 50) lista.removeAt(0)
    }

    private suspend fun descargarHistorialInicial(s: String) = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.bitget.com/api/v2/mix/market/candles?symbol=$s&productType=USDT-FUTURES&granularity=1m&limit=50"
            val body = client.newCall(Request.Builder().url(url).build()).execute().body?.string()
            val data = JSONObject(body ?: "{}").getJSONArray("data")
            val precios = mutableListOf<Double>()
            for (i in 0 until data.length()) { precios.add(data.getJSONArray(i).getString(4).toDouble()) }
            historialPreciosMap[s] = precios
        } catch (e: Exception) {}
    }

    private fun actualizarEstadoUI(mensaje: String) {
        val i = Intent("ACTUALIZACION_TRADING")
        i.putExtra("STATUS_MSG", mensaje)
        i.putExtra("BALANCE", currentBalance)
        i.putExtra("IS_TRADE_OPEN", posicionAbierta)
        if (posicionAbierta) {
            val pnl = calcularPnL(lastPrice.toDoubleOrNull() ?: precioEntrada, true)
            val pnlCash = (pnl / 100) * (currentBalance * (riskPercent / 100.0))
            i.putExtra("TRADE_SYMBOL", monedaConPosicion)
            i.putExtra("TRADE_TYPE", tipoPosicion)
            i.putExtra("TRADE_PNL", pnl)
            i.putExtra("TRADE_PNL_CASH", pnlCash)
        }
        sendBroadcast(i)
    }

    private suspend fun obtenerBalanceBitget(): Double? = withContext(Dispatchers.IO) {
        val resp = BitgetUtils.authenticatedGet("/api/v2/mix/account/accounts?productType=USDT-FUTURES", apiKey, apiSecret, apiPassphrase)
        try { JSONObject(resp ?: "{}").getJSONArray("data").getJSONObject(0).getString("available").toDouble() } catch (e: Exception) { null }
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
        leverage = p.getInt("LEVERAGE", 5).toDouble()
        riskPercent = p.getString("RISK_PERCENT", "20.0")?.toDoubleOrNull() ?: 20.0
        activeSymbols.clear()
        if (p.getBoolean("COIN_BTC", true)) activeSymbols.add("BTCUSDT")
        if (p.getBoolean("COIN_ETH", false)) activeSymbols.add("ETHUSDT")
        if (p.getBoolean("COIN_SOL", false)) activeSymbols.add("SOLUSDT")
        if (p.getBoolean("COIN_XRP", false)) activeSymbols.add("XRPUSDT")
        if (activeSymbols.isEmpty()) activeSymbols.add("BTCUSDT")
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
        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle(t)
            .setContentText(c)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }

    private fun enviarAlertaPush(t: String, m: String, c: Boolean) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
            .setContentTitle(t)
            .setContentText(m)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .build()
        getSystemService(NotificationManager::class.java).notify(System.currentTimeMillis().toInt(), notif)
    }

    override fun onDestroy() {
        isRunning = false
        job?.cancel()
        if(isReceiverRegistered) unregisterReceiver(manualCloseReceiver)
        super.onDestroy()
    }
}