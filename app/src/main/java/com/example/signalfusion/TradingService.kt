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

    private val client = OkHttpClient()
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val CHANNEL_ID_FOREGROUND = "SignalFusionChannel"
    private val CHANNEL_ID_ALERTS = "SignalFusionAlerts"

    private lateinit var manualCloseReceiver: BroadcastReceiver
    private var isReceiverRegistered = false

    // 🔧 VALORES SEGUROS POR DEFECTO
    private var targetTP = 3.0
    private var targetSL = 5.0 // SL Base amplio (5%)
    private var leverage = 10.0 // Valor inicial (se sobrescribe con tu ajuste)
    private var riskPercent = 3.0
    private var activeStrategy = "MODERADA"
    private var candleTimeframe = "15m"
    private var candleIntervalMs = 900_000L

    // Variables internas
    private var lastCandleTime = 0L
    private var maxPnLAlcanzado = 0.0
    private var tsActivation = 2.2
    private var tsCallback = 0.4
    private var initialBalance = 0.0
    private var maxDailyLoss = 10.0
    private var apiKey = ""
    private var apiSecret = ""
    private var apiPassphrase = ""
    private var activeSymbols = mutableListOf<String>()
    private var currentSymbolIndex = 0
    private val historialPreciosMap = mutableMapOf<String, MutableList<Double>>()

    // Cooldown
    private var ultimaOperacionTime = 0L
    private var consecutiveLosses = 0
    private val baseCooldown = 900_000L // 15 minutos

    private val ultimateStrategies = mutableMapOf<String, SignalFusionUltimateStrategy>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanalesNotificacion()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SignalFusion::TradingWakeLock")
        wakeLock?.acquire(24 * 60 * 60 * 1000L)

        manualCloseReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "FORZAR_CIERRE_MANUAL") {
                    if (posicionAbierta) {
                        agregarLog("⚠️ PÁNICO: Ejecutando cierre...")
                        CoroutineScope(Dispatchers.IO).launch { cerrarTradeReal("Manual Panic") }
                    } else {
                        actualizarEstadoUI("Limpiando UI...")
                    }
                }
                if (intent?.action == "SOLICITAR_REFRESH_UI" || intent?.action == "SOLICITAR_REFRESH_BALANCE") {
                    CoroutineScope(Dispatchers.IO).launch {
                        val nuevoBalance = obtenerBalanceBitget()
                        if (nuevoBalance != null) {
                            currentBalance = nuevoBalance
                        }
                        actualizarEstadoUI("Sincronizado")
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
            if (saldo != null && saldo > 0) {
                currentBalance = saldo
                if (initialBalance == 0.0) initialBalance = currentBalance
                isRunning = true
                agregarLog("✅ MOTOR V5.2 | TF: $candleTimeframe | Lev: ${leverage.toInt()}x")
                iniciarCicloTrading()
            } else {
                agregarLog("❌ ERROR: Revisa API Keys")
                stopSelf()
            }
        }
        startForeground(1, createForegroundNotification("SignalFusion Ultimate", "Motor Cuantitativo Activo"))
        return START_STICKY
    }

    private fun iniciarCicloTrading() {
        job = CoroutineScope(Dispatchers.IO).launch {
            activeSymbols.forEach { descargarHistorialInicial(it); delay(500) }

            while (isActive) {
                try {
                    if (posicionAbierta) verificarIntegridadPosicion()

                    if (currentBalance < initialBalance * (1 - (maxDailyLoss / 100.0))) {
                        agregarLog("⛔ CIRCUIT BREAKER. Apagando."); stopSelf(); break
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

                } catch (e: Exception) { agregarLog("⚠️ Loop: ${e.message}") }
                delay(if (posicionAbierta) 2000 else 3000)
            }
        }
    }

    private fun ejecutarEstrategiaUltimate(symbol: String, precio: Double, hP: List<Double>) {
        if (System.currentTimeMillis() < ultimaOperacionTime) {
            val tiempoRestante = (ultimaOperacionTime - System.currentTimeMillis()) / 1000
            actualizarEstadoUI("⏳ Cooldown: ${tiempoRestante}s")
            return
        }

        val atr = Indicadores.calcularATR(hP, 14)
        val atrPercent = (atr / precio) * 100

        // Filtro ATR 0.8%
        if (atrPercent < 0.8) {
            actualizarEstadoUI("⏸️ ATR Bajo (${"%.2f".format(atrPercent)}%), esperando...")
            return
        }

        // Detector Lateral
        if (hP.size >= 20) {
            val atr5ago = Indicadores.calcularATR(hP.dropLast(5), 14)
            if (atr < atr5ago * 0.8) {
                actualizarEstadoUI("📊 Mercado lateralizando...")
                return
            }
        }

        val rsi = Indicadores.calcularRSI(hP, 14)
        val rsiMA = Indicadores.calcularRSIMA(hP, 14, 7)
        val emas = Indicadores.calcularEMAs(hP)
        val bb = Indicadores.calcularBollingerBands(hP)
        val macd = Indicadores.calcularMACD(hP)

        lastRSI = "%.0f".format(rsi)
        actualizarEstadoUI("Analizando $symbol | RSI: $lastRSI")

        val marketData = MarketData(precio, precio*1.005, precio*0.995, rsi, rsiMA, emas.fast, emas.slow, emas.mid, emas.trend, bb.first, bb.second, bb.third, macd.first, macd.second, macd.third)
        val cerebro = ultimateStrategies[symbol] ?: return
        val senialFinal = cerebro.evaluate(marketData)

        if (senialFinal == "LONG") abrirTradeReal(symbol, precio, "LONG", hP)
        else if (senialFinal == "SHORT") abrirTradeReal(symbol, precio, "SHORT", hP)
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
            // Bypass Seguro: Max 40% de cuenta o $6 si es cuenta pequeña
            val margenDeseado = if (currentBalance < 50.0) {
                min(currentBalance * 0.40, 6.0)
            } else {
                currentBalance * (riskPercent / 100.0)
            }

            // ✅ USAMOS TU LEVERAGE SELECCIONADO:
            // Bitget necesita el tamaño total de la posición (Margen * Leverage).
            // Si en la App pones 10x y en Bitget tienes 10x, todo cuadra perfecto.
            val sizeAmount = (margenDeseado * leverage) / precio

            var precisionSize = 1
            var precisionPrice = 2
            if (symbol.contains("BTC")) { precisionSize = 3; precisionPrice = 1 }
            else if (symbol.contains("ETH")) { precisionSize = 2; precisionPrice = 2 }
            else if (symbol.contains("SOL")) { precisionSize = 1; precisionPrice = 2 }
            else if (symbol.contains("XRP")) { precisionSize = 0; precisionPrice = 4 }

            val sizeStr = String.format(Locale.US, "%.${precisionSize}f", sizeAmount)
            val sizeFinal = sizeStr.toDouble()

            if (sizeFinal <= 0.0) return@launch

            // ✅ SL INTELIGENTE basado en ATR y Leverage seleccionado
            val atr = Indicadores.calcularATR(hP, 14)
            val minSLDist = precio * (targetSL / 100.0 / leverage)
            val minTPDist = precio * (targetTP / 100.0 / leverage)

            val finalSLDist = max(atr * 2.0, minSLDist)
            val finalTPDist = max(atr * 4.0, minTPDist)

            val slPrice = if (tipo == "LONG") precio - finalSLDist else precio + finalSLDist
            val tpPrice = if (tipo == "LONG") precio + finalTPDist else precio - finalTPDist

            val slStr = String.format(Locale.US, "%.${precisionPrice}f", slPrice)
            val tpStr = String.format(Locale.US, "%.${precisionPrice}f", tpPrice)

            agregarLog("🎯 SEÑAL $tipo ($symbol)")

            val json = JSONObject()
            json.put("symbol", symbol)
            json.put("productType", "usdt-futures")
            json.put("marginCoin", "USDT")
            json.put("marginMode", "crossed")
            json.put("side", if (tipo == "LONG") "buy" else "sell")
            json.put("tradeSide", "open")
            json.put("orderType", "market")
            json.put("size", sizeStr)
            json.put("presetStopLossPrice", slStr)
            json.put("presetTakeProfitPrice", tpStr)

            val resp = BitgetUtils.authenticatedPost("/api/v2/mix/order/place-order", json.toString(), apiKey, apiSecret, apiPassphrase)

            if (resp != null && resp.contains("00000")) {
                posicionAbierta = true
                monedaConPosicion = symbol
                tipoPosicion = tipo
                precioEntrada = precio
                maxPnLAlcanzado = -0.2
                // Cooldown 15 min base
                ultimaOperacionTime = System.currentTimeMillis() + baseCooldown
                agregarLog("✅ EJECUTADO: $tipo $symbol")
                actualizarEstadoUI("🚀 POSICIÓN ACTIVA")
                enviarAlertaPush("NUEVA POSICIÓN 🚀", "$tipo $symbol", false)
            } else {
                agregarLog("❌ RECHAZADO: $resp")
            }
        }
    }

    private fun cerrarTradeReal(motivo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            agregarLog("📤 CERRANDO ($motivo)...")
            val json = JSONObject()
            json.put("symbol", monedaConPosicion)
            json.put("productType", "usdt-futures")
            json.put("marginCoin", "USDT")
            json.put("holdSide", if (tipoPosicion == "LONG") "long" else "short")

            val resp = BitgetUtils.authenticatedPost("/api/v2/mix/order/close-positions", json.toString(), apiKey, apiSecret, apiPassphrase)

            if (resp != null && (resp.contains("00000") || resp.contains("success"))) {
                val pnlFinal = calcularPnL(lastPrice.toDoubleOrNull() ?: precioEntrada, true)

                // Cooldown Adaptativo
                if (pnlFinal < 0) {
                    consecutiveLosses++
                    val multiplier = 1.0 + (consecutiveLosses * 0.5)
                    val adaptiveCooldown = (baseCooldown * multiplier).toLong()
                    ultimaOperacionTime = System.currentTimeMillis() + adaptiveCooldown
                    agregarLog("⚠️ Pérdida #$consecutiveLosses. Pausa de ${adaptiveCooldown/60000} min.")
                } else {
                    consecutiveLosses = 0
                    ultimaOperacionTime = System.currentTimeMillis() + baseCooldown
                }

                posicionAbierta = false
                monedaConPosicion = ""
                maxPnLAlcanzado = 0.0
                obtenerBalanceBitget()?.let { currentBalance = it }
                agregarLog("🏁 CERRADO: PnL = ${"%.2f".format(pnlFinal)}%")
                actualizarEstadoUI("Buscando entradas...")
                enviarAlertaPush("CERRADO 💰", "PnL: ${"%.2f".format(pnlFinal)}%", true)
            } else {
                agregarLog("❌ ERROR AL CERRAR: $resp")
            }
        }
    }

    private fun calcularPnL(actual: Double, conFees: Boolean): Double {
        if (precioEntrada <= 0) return 0.0
        val diff = if (tipoPosicion == "LONG") actual - precioEntrada else precioEntrada - actual
        val bruto = (diff / precioEntrada) * 100 * leverage

        if (conFees) {
            val feeRate = 0.06
            val slippageRate = 0.15
            val totalCost = (feeRate * 2) + slippageRate
            val feeCost = totalCost * leverage
            return bruto - feeCost
        }
        return bruto
    }

    private fun actualizarDatosVela(symbol: String, precio: Double) {
        val lista = historialPreciosMap[symbol] ?: return
        lista.add(precio)
        if (lista.size > 250) lista.removeAt(0)
    }

    private suspend fun descargarHistorialInicial(s: String) = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.bitget.com/api/v2/mix/market/candles?symbol=$s&productType=USDT-FUTURES&granularity=$candleTimeframe&limit=200"
            val body = client.newCall(Request.Builder().url(url).build()).execute().body?.string()
            val data = JSONObject(body ?: "{}").getJSONArray("data")
            val precios = mutableListOf<Double>()
            for (i in data.length() - 1 downTo 0) {
                precios.add(data.getJSONArray(i).getString(4).toDouble())
            }
            historialPreciosMap[s] = precios
            agregarLog("✅ $s: ${precios.size} velas cargadas")
        } catch (e: Exception) {
            agregarLog("⚠️ Error cargando $s: ${e.message}")
        }
    }

    private suspend fun verificarIntegridadPosicion() {
        try {
            val endpoint = "/api/v2/mix/position/single-position?symbol=$monedaConPosicion&productType=usdt-futures&marginCoin=USDT"
            val resp = BitgetUtils.authenticatedGet(endpoint, apiKey, apiSecret, apiPassphrase)
            if (resp != null) {
                val json = JSONObject(resp)
                if (json.optString("code") == "00000") {
                    val data = json.optJSONArray("data")
                    if (data == null || data.length() == 0 || data.getJSONObject(0).optString("total", "0").toDouble() == 0.0) {
                        agregarLog("⚠️ La posición se cerró externamente.")
                        posicionAbierta = false
                        monedaConPosicion = ""
                        maxPnLAlcanzado = 0.0
                        actualizarEstadoUI("Sincronizado")
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
        try {
            val bal = JSONObject(resp ?: "{}").getJSONArray("data").getJSONObject(0).getString("available").toDouble()
            val p = getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
            p.edit().putString("LAST_KNOWN_BALANCE", bal.toString()).apply()
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

        // ✅ CORRECCIÓN FINAL: Lee tu leverage de la App, no lo fija a 10
        leverage = p.getInt("LEVERAGE", 10).toDouble()

        // Leemos el riesgo de la app
        riskPercent = p.getString("RISK_PERCENT", "3.0")?.toDoubleOrNull() ?: 3.0

        // Bypass: Si hay poco saldo, la lógica en abrirTradeReal maneja el monto seguro.

        targetTP = p.getString("TP_VAL", "3.0")?.toDoubleOrNull() ?: 3.0
        targetSL = p.getString("SL_VAL", "5.0")?.toDoubleOrNull() ?: 5.0
        activeStrategy = p.getString("STRATEGY", "MODERADA") ?: "MODERADA"

        val tfStr = p.getString("TIMEFRAME_VAL", "15m") ?: "15m"
        candleTimeframe = tfStr
        candleIntervalMs = if (tfStr == "15m") 900_000L else 300_000L

        activeSymbols.clear()
        if (p.getBoolean("COIN_BTC", true)) activeSymbols.add("BTCUSDT")
        if (p.getBoolean("COIN_ETH", false)) activeSymbols.add("ETHUSDT")
        if (p.getBoolean("COIN_SOL", false)) activeSymbols.add("SOLUSDT")
        if (p.getBoolean("COIN_XRP", false)) activeSymbols.add("XRPUSDT")
        if (activeSymbols.isEmpty()) activeSymbols.add("BTCUSDT")

        ultimateStrategies.clear()
        activeSymbols.forEach { symbol ->
            ultimateStrategies[symbol] = SignalFusionUltimateStrategy(candleTimeframe, activeStrategy)
        }
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
        try { wakeLock?.release() } catch (e: Exception) {}
        if(isReceiverRegistered) unregisterReceiver(manualCloseReceiver)
        super.onDestroy()
    }
}