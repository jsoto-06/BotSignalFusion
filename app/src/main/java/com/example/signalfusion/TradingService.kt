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
        var lastATR = "--"
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

    // ✅ V9: Parámetros correctos para scalping coherente en 15m
    private var targetTP = V9TradingParams.TP_SCALPING          // 2.0%
    private var targetSL = V9TradingParams.SL_SCALPING          // 0.9%
    private var tsActivation = V9TradingParams.TRAILING_ACTIVATION  // 1.8%
    private var tsCallback = V9TradingParams.TRAILING_CALLBACK      // 0.8%

    private var leverage = 10.0
    private var riskPercent = 5.0
    private var activeStrategy = "MODERADA"
    private var candleTimeframe = "15m"
    private var candleIntervalMs = 900_000L
    private var lastCandleTime = 0L
    private var maxPnLAlcanzado = 0.0
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
    // Símbolos que Bitget rechazó en DEMO (ej: SSOLSUSDT no existe en simulación)
    private val failedSymbols = mutableSetOf<String>()
    private val symbolFailCount = mutableMapOf<String, Int>()

    // ✅ V9: Estrategias por símbolo
    private val ultimateStrategies = mutableMapOf<String, SignalFusionV9Strategy>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        agregarLog("🔧 onCreate() llamado")
        crearCanalesNotificacion()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "SignalFusion::TradingWakeLock"
        )
        wakeLock?.acquire()
        agregarLog("⚡ WakeLock adquirido")

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
        agregarLog("🚀 onStartCommand() llamado")
        sincronizarAjustes()

        CoroutineScope(Dispatchers.IO).launch {
            agregarLog("💰 Obteniendo balance de Bitget (${if (BitgetConfig.isDemo()) "DEMO" else "REAL"})...")
            val saldo = obtenerBalanceBitget()
            if (saldo != null && saldo >= 0) {
                currentBalance = saldo
                if (initialBalance == 0.0) initialBalance = currentBalance
                isRunning = true
                agregarLog("✅ MOTOR V9 ${if (BitgetConfig.isDemo()) "DEMO" else "REAL"} ACTIVO | Balance: $${"%.2f".format(currentBalance)}")
                agregarLog("📊 TP: ${"%.1f".format(targetTP)}% | SL: ${"%.1f".format(targetSL)}% | R:R ${"%.1f".format(targetTP / targetSL)}:1")
                agregarLog("🛡️ Trailing: activación ${tsActivation}% | callback ${tsCallback}%")
                agregarLog("🎯 Estrategia: $activeStrategy | Timeframe: $candleTimeframe")
                agregarLog("🪙 Monedas activas: ${activeSymbols.joinToString(", ")}")
                cargarHistorialCerradas()
                activeSymbols.forEach {
                    agregarLog("🔧 Configurando leverage para $it...")
                    setearLeverage(it, "LONG")
                }
                iniciarCicloTrading()
            } else {
                agregarLog("❌ ERROR: No se pudo obtener balance. Revisa API Keys")
                stopSelf()
            }
        }
        startForeground(
            1,
            createForegroundNotification(
                "SignalFusion V9",
                if (BitgetConfig.isDemo()) "Modo DEMO" else "Modo REAL"
            )
        )
        return START_STICKY
    }

    // ✅ FIX: Símbolo correcto según DEMO/REAL
    private fun getApiSymbol(baseSymbol: String): String {
        return if (BitgetConfig.isDemo()) {
            "S" + baseSymbol.replace("USDT", "SUSDT")
        } else {
            baseSymbol
        }
    }

    private suspend fun cargarHistorialCerradas() = withContext(Dispatchers.IO) {
        try {
            // ✅ FIX: Usa BitgetConfig.getProductType() en lugar de hardcodear USDT-FUTURES
            val endpoint = "/api/v2/mix/order/history-orders?productType=${BitgetConfig.getProductType()}&limit=100"
            val resp = BitgetUtils.authenticatedGet(endpoint, apiKey, apiSecret, apiPassphrase)

            if (resp != null) {
                val json = JSONObject(resp)
                if (json.optString("code") == "00000") {
                    val data = json.optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        var wins = 0
                        var losses = 0
                        var dailyPnl = 0.0
                        val now = System.currentTimeMillis()
                        val unDiaMs = 24L * 60 * 60 * 1000L

                        for (i in 0 until data.length()) {
                            val order = data.getJSONObject(i)
                            var pnl = order.optDouble("profit", 0.0)
                            if (pnl == 0.0) pnl = order.optDouble("realizedPL", 0.0)
                            if (pnl == 0.0) pnl = order.optDouble("netProfit", 0.0)

                            if (pnl != 0.0) {
                                if (pnl > 0) wins++ else losses++
                                val uTime = order.optLong("uTime", order.optLong("cTime", now))
                                if (now - uTime <= unDiaMs) dailyPnl += pnl
                            }
                        }
                        val total = wins + losses
                        val wr = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
                        val broadcastIntent = Intent("ACTUALIZACION_ESTADISTICAS")
                        broadcastIntent.putExtra("WIN_RATE", "${"%.1f".format(wr)}%")
                        broadcastIntent.putExtra("DAILY_PNL", dailyPnl)
                        sendBroadcast(broadcastIntent)
                    } else {
                        val broadcastIntent = Intent("ACTUALIZACION_ESTADISTICAS")
                        broadcastIntent.putExtra("WIN_RATE", "0.0%")
                        broadcastIntent.putExtra("DAILY_PNL", 0.0)
                        sendBroadcast(broadcastIntent)
                    }
                }
            }
        } catch (e: Exception) {
            agregarLog("⚠️ Error Historial: ${e.message}")
        }
    }

    private fun iniciarCicloTrading() {
        job = CoroutineScope(Dispatchers.IO).launch {
            activeSymbols.forEach {
                descargarHistorialInicial(it)
                delay(500)
            }
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeat > heartbeatInterval) {
                        lastHeartbeat = now
                        agregarLog("💓 Heartbeat | Balance: $${"%.2f".format(currentBalance)}")
                    }
                    if (posicionAbierta) verificarIntegridadPosicion()

                    // Saltar símbolos fallidos y reindexar si es necesario
                    if (activeSymbols.isEmpty()) {
                        actualizarEstadoUI("⚠️ Sin símbolos disponibles en ${BitgetConfig.getProductType()}")
                        delay(10_000)
                        continue
                    }
                    if (currentSymbolIndex >= activeSymbols.size) currentSymbolIndex = 0
                    val symbol = if (posicionAbierta) monedaConPosicion else activeSymbols[currentSymbolIndex]
                    val precio = obtenerPrecioReal(symbol)

                    if (precio > 0) {
                        lastPrice = "%.2f".format(precio)
                        if (System.currentTimeMillis() - lastCandleTime >= candleIntervalMs) {
                            actualizarDatosVela(symbol, precio)
                            lastCandleTime = System.currentTimeMillis()
                        }
                        val hP = historialPreciosMap[symbol] ?: mutableListOf()

                        if (hP.size >= 50) {
                            val rsiRaw = try { Indicadores.calcularRSI(hP, 14) } catch (e: Exception) { 0.0 }
                            val atrRaw = try { Indicadores.calcularATR(hP, 14) } catch (e: Exception) { 0.0 }
                            val atrPercentRaw = if (precio > 0) (atrRaw / precio) * 100 else 0.0

                            lastRSI = "%.0f".format(rsiRaw)
                            lastATR = "%.2f".format(atrPercentRaw)

                            if (!posicionAbierta) {
                                ejecutarEstrategiaUltimate(symbol, precio, hP, rsiRaw, atrPercentRaw)
                            } else {
                                gestionarSalida(symbol, precio)
                            }
                        } else {
                            android.util.Log.d("BOT_DEBUG", "📥 Calibrando $symbol: ${hP.size}/50 velas | precio=${"%.2f".format(precio)}")
                            actualizarEstadoUI("📥 Calibrando $symbol (${hP.size}/50)")
                        }
                    }
                    if (!posicionAbierta && activeSymbols.isNotEmpty()) {
                        currentSymbolIndex = (currentSymbolIndex + 1) % activeSymbols.size
                    }
                    errorCount = 0
                } catch (e: Exception) {
                    errorCount++
                    if (errorCount > 3) {
                        stopSelf()
                        break
                    } else {
                        delay(5000)
                    }
                }
                delay(if (posicionAbierta) 2000 else 3000)
            }
        }
    }

    // ✅ V9: Evaluación completa con rsiMA real y logging de rechazos
    private fun ejecutarEstrategiaUltimate(
        symbol: String,
        precio: Double,
        hP: List<Double>,
        rsi: Double,
        atrPercent: Double
    ) {
        if (System.currentTimeMillis() < ultimaOperacionTime) {
            actualizarEstadoUI("⏳ Cooldown... (RSI: $lastRSI | ATR: $lastATR%)")
            return
        }

        if (atrPercent < 0.25) {
            actualizarEstadoUI("⏸️ ATR Bajo | $symbol RSI: $lastRSI ATR: $lastATR%")
            return
        }

        val emas = Indicadores.calcularEMAs(hP)
        val bb = Indicadores.calcularBollingerBands(hP)
        val macd = Indicadores.calcularMACD(hP)

        // ✅ FIX CRÍTICO: rsiMA calculado independientemente del RSI actual
        val rsiMA = try { Indicadores.calcularRSIMA(hP, 14, 7) } catch (e: Exception) { rsi }

        actualizarEstadoUI("Analizando $symbol | RSI: $lastRSI | ATR: $lastATR%")

        val marketData = MarketData(
            price = precio,
            high = precio * 1.005,
            low = precio * 0.995,
            rsi = rsi,
            rsiMA = rsiMA,              // ✅ FIX: Ahora es distinto del RSI actual
            emaFast = emas.fast,
            emaSlow = emas.slow,
            emaMid = emas.mid,
            emaTrend = emas.trend,
            bbUpper = bb.first,
            bbMiddle = bb.second,
            bbLower = bb.third,
            macdLine = macd.first,
            macdSignal = macd.second,
            macdHist = macd.third
            // htfEmaFast, htfEmaTrend, htfMacdHist: dejar en 0.0 (proxy automático activo)
        )

        val cerebro = ultimateStrategies[symbol] ?: return
        val result = cerebro.evaluate(marketData)

        if (result.signal == "NEUTRAL") {
            val msg = "🚫 [$symbol] RECHAZADO → ${result.rejectionReason}"
            android.util.Log.d("BOT_DEBUG", msg)
            actualizarEstadoUI("🚫 $symbol: ${result.rejectionReason?.take(45)}")
        }

        if ((result.signal == "LONG" || result.signal == "SHORT") && !posicionAbierta) {
            agregarLog("✅✅✅ SEÑAL V9 VÁLIDA: ${result.signal} en $symbol ✅✅✅")
            agregarLog("📊 Régimen: ${result.regime} | Confirmaciones: ${result.confirmations}/3")
            agregarLog("🗳️ Votos → RSI: ${result.familyVotes.rsiVote} | EMA: ${result.familyVotes.emaVote} | MACD: ${result.familyVotes.macdVote}")
            posicionAbierta = true
            monedaConPosicion = symbol
            tipoPosicion = result.signal
            abrirTradeReal(symbol, precio, result.signal)
        }
    }

    private fun gestionarSalida(symbol: String, precio: Double) {
        val pnlNeto = calcularPnL(precio, true)
        if (pnlNeto > maxPnLAlcanzado) maxPnLAlcanzado = pnlNeto

        actualizarEstadoUI("Operando $symbol: ${"%.2f".format(pnlNeto)}% | Max: ${"%.2f".format(maxPnLAlcanzado)}%")

        // ✅ V9: Trailing con parámetros coherentes (1.8% activación / 0.8% callback)
        if (maxPnLAlcanzado >= tsActivation && (maxPnLAlcanzado - pnlNeto) >= tsCallback) {
            agregarLog("🛡️ Trailing Stop V9: Max ${"%.2f".format(maxPnLAlcanzado)}% → Actual ${"%.2f".format(pnlNeto)}%")
            cerrarTradeReal("Trailing Stop 🛡️")
        }
    }

    private fun abrirTradeReal(symbol: String, precio: Double, tipo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            agregarLog("🎯 === ABRIENDO TRADE (${if (BitgetConfig.isDemo()) "DEMO" else "REAL"}) ===")
            setearLeverage(symbol, tipo)

            val margenDeseado = when {
                currentBalance < 15.0 -> min(currentBalance * 0.40, 5.5)
                currentBalance < 20.0 -> min(currentBalance * 0.30, 6.0)
                currentBalance < 50.0 -> min(currentBalance * 0.15, 7.5)
                else -> currentBalance * (riskPercent / 100.0)
            }

            val sizeAmount = (margenDeseado * leverage) / precio
            var precisionSize = 1
            var precisionPrice = 2

            when {
                symbol.contains("BTC") -> { precisionSize = 3; precisionPrice = 1 }
                symbol.contains("ETH") -> { precisionSize = 2; precisionPrice = 2 }
                symbol.contains("SOL") -> { precisionSize = 1; precisionPrice = 3 }
                symbol.contains("XRP") -> { precisionSize = 0; precisionPrice = 3 }
            }

            val sizeStr = String.format(Locale.US, "%.${precisionSize}f", sizeAmount)
            val sizeFinal = sizeStr.toDoubleOrNull() ?: 0.0

            if (sizeFinal <= 0.0 || currentBalance <= 0.5) {
                agregarLog("⚠️ Saldo insuficiente")
                posicionAbierta = false
                monedaConPosicion = ""
                return@launch
            }

            // ✅ V9: TP/SL calculados con los parámetros correctos (2.0% / 0.9%)
            val distanciaSL = precio * (targetSL / 100.0 / leverage)
            val distanciaTP = precio * (targetTP / 100.0 / leverage)
            val slPriceRaw = if (tipo == "LONG") precio - distanciaSL else precio + distanciaSL
            val tpPriceRaw = if (tipo == "LONG") precio + distanciaTP else precio - distanciaTP

            val factorPrecio = Math.pow(10.0, precisionPrice.toDouble())
            val slPrice = Math.round(slPriceRaw * factorPrecio) / factorPrecio
            val tpPrice = Math.round(tpPriceRaw * factorPrecio) / factorPrecio

            val apiSymbol = getApiSymbol(symbol)

            val json = JSONObject().apply {
                put("symbol", apiSymbol)
                put("productType", BitgetConfig.getProductType())
                put("marginCoin", BitgetConfig.getMarginCoin())
                put("marginMode", "crossed")
                put("side", if (tipo == "LONG") "buy" else "sell")
                put("tradeSide", "open")
                put("orderType", "market")
                put("size", sizeStr)
                put("presetStopLossPrice", String.format(Locale.US, "%.${precisionPrice}f", slPrice))
                put("presetTakeProfitPrice", String.format(Locale.US, "%.${precisionPrice}f", tpPrice))
            }

            agregarLog("📤 Enviando orden | TP: ${"%.${precisionPrice}f".format(tpPrice)} | SL: ${"%.${precisionPrice}f".format(slPrice)}")
            val resp = BitgetUtils.authenticatedPost(
                "/api/v2/mix/order/place-order",
                json.toString(),
                apiKey, apiSecret, apiPassphrase
            )

            if (resp != null && (resp.contains("\"code\":\"00000\"") || resp.contains("\"msg\":\"success\""))) {
                delay(1500)
                precioEntrada = precio
                maxPnLAlcanzado = -0.2
                ultimaOperacionTime = System.currentTimeMillis() + baseCooldown
                agregarLog("✅✅✅ ORDEN EJECUTADA ✅✅✅")
                actualizarEstadoUI("🚀 POSICIÓN ACTIVA")
                TelegramNotifier.enviarEntrada(applicationContext, symbol, tipo, precio)
            } else {
                val errorMsg = if (resp != null) {
                    try { JSONObject(resp).optString("msg", resp) } catch (e: Exception) { resp }
                } else "Timeout/Error red"

                agregarLog("❌ RECHAZO BITGET: $errorMsg")
                posicionAbierta = false
                monedaConPosicion = ""
                ultimaOperacionTime = System.currentTimeMillis() + 60_000L
            }
        }
    }

    private suspend fun setearLeverage(symbol: String, tipo: String) = withContext(Dispatchers.IO) {
        try {
            val apiSymbol = getApiSymbol(symbol)
            val json = JSONObject().apply {
                put("symbol", apiSymbol)
                put("productType", BitgetConfig.getProductType())
                put("marginCoin", BitgetConfig.getMarginCoin())
                put("leverage", leverage.toInt().toString())
                put("holdSide", if (tipo == "LONG") "long" else "short")
            }
            BitgetUtils.authenticatedPost(
                "/api/v2/mix/account/set-leverage",
                json.toString(),
                apiKey, apiSecret, apiPassphrase
            )
        } catch (e: Exception) {}
    }

    private fun cerrarTradeReal(motivo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            if (!posicionAbierta) return@launch
            val symTemp = monedaConPosicion
            val sideTemp = tipoPosicion
            val apiSymbol = getApiSymbol(monedaConPosicion)

            val json = JSONObject().apply {
                put("symbol", apiSymbol)
                put("productType", BitgetConfig.getProductType())
                put("marginCoin", BitgetConfig.getMarginCoin())
                put("holdSide", if (tipoPosicion == "LONG") "long" else "short")
            }
            val resp = BitgetUtils.authenticatedPost(
                "/api/v2/mix/order/close-positions",
                json.toString(),
                apiKey, apiSecret, apiPassphrase
            )

            if (resp != null && (resp.contains("\"code\":\"00000\"") || resp.contains("\"msg\":\"success\""))) {
                val precioCierre = lastPrice.toDoubleOrNull() ?: precioEntrada
                val pnlFinal = calcularPnL(precioCierre, true)

                // ✅ V9: Feedback al gestor de estado para que active cooldown si hay pérdidas
                ultimateStrategies[symTemp]?.reportResult(pnlFinal > 0)

                if (pnlFinal < 0) {
                    consecutiveLosses++
                    val cooldownMin = 15 + (consecutiveLosses * 10)
                    ultimaOperacionTime = System.currentTimeMillis() + (cooldownMin * 60_000L)
                    agregarLog("❌ LOSS #$consecutiveLosses → Cooldown: ${cooldownMin}min")
                } else {
                    consecutiveLosses = 0
                    ultimaOperacionTime = System.currentTimeMillis() + baseCooldown
                    agregarLog("✅ WIN → Cooldown base: ${baseCooldown / 60_000}min")
                }

                posicionAbierta = false
                monedaConPosicion = ""
                maxPnLAlcanzado = 0.0
                obtenerBalanceBitget()?.let { currentBalance = it }
                agregarLog("🏁 TRADE CERRADO | Motivo: $motivo | PnL: ${"%.2f".format(pnlFinal)}%")
                actualizarEstadoUI("Escaneando...")
                TelegramNotifier.enviarSalida(applicationContext, symTemp, sideTemp, pnlFinal, precioCierre)
                cargarHistorialCerradas()
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
        lista.add(precio)
        if (lista.size > 250) lista.removeAt(0)
    }

    // ✅ FIX: URL usa BitgetConfig.getProductType() — funciona en DEMO y REAL
    private suspend fun descargarHistorialInicial(s: String) = withContext(Dispatchers.IO) {
        try {
            val apiSym = getApiSymbol(s)
            val url = "https://api.bitget.com/api/v2/mix/market/candles" +
                    "?symbol=${apiSym}&productType=${BitgetConfig.getProductType()}" +
                    "&granularity=${candleTimeframe}&limit=200"

            agregarLog("📡 Descargando velas: $apiSym (${BitgetConfig.getProductType()})")
            val body = client.newCall(Request.Builder().url(url).build())
                .execute().body?.string() ?: run {
                markSymbolFailed(s, "Respuesta nula")
                return@withContext
            }

            android.util.Log.d("BOT_DEBUG", "Candles [$apiSym]: ${body.take(120)}")
            val json = JSONObject(body)
            val code = json.optString("code")
            if (code != "00000") {
                val msg = json.optString("msg", "error desconocido")
                agregarLog("❌ Candles error [$code] $apiSym: $msg")
                markSymbolFailed(s, msg)
                return@withContext
            }

            val data = json.getJSONArray("data")
            val precios = mutableListOf<Double>()
            for (i in data.length() - 1 downTo 0) {
                precios.add(data.getJSONArray(i).getString(4).toDouble())
            }
            historialPreciosMap[s] = precios
            agregarLog("✅ $apiSym: ${precios.size} velas cargadas")
        } catch (e: Exception) {
            markSymbolFailed(s, e.message ?: "excepción")
        }
    }

    /** Marca un símbolo como fallido y lo elimina de activeSymbols para no spamear errores */
    private fun markSymbolFailed(s: String, reason: String) {
        val count = (symbolFailCount[s] ?: 0) + 1
        symbolFailCount[s] = count
        agregarLog("⚠️ ${getApiSymbol(s)} fallo #$count: $reason")
        if (count >= 2) {
            failedSymbols.add(s)
            activeSymbols.remove(s)
            ultimateStrategies.remove(s)
            agregarLog("🚫 ${getApiSymbol(s)} eliminado — no disponible en este modo (${BitgetConfig.getProductType()})")
            android.util.Log.e("BOT_DEBUG", "🚫 Símbolo $s (${getApiSymbol(s)}) desactivado: $reason")
        }
    }

    private suspend fun obtenerPrecioReal(s: String): Double = withContext(Dispatchers.IO) {
        // Si el símbolo fue marcado como no disponible, devolver 0 silenciosamente
        if (s in failedSymbols) return@withContext 0.0
        try {
            val apiSym = getApiSymbol(s)
            val url = "https://api.bitget.com/api/v2/mix/market/ticker" +
                    "?symbol=${apiSym}&productType=${BitgetConfig.getProductType()}"

            val body = client.newCall(Request.Builder().url(url).build())
                .execute().body?.string()

            val json = JSONObject(body ?: "{}")
            if (json.optString("code") != "00000") {
                markSymbolFailed(s, json.optString("msg", "ticker error"))
                return@withContext 0.0
            }
            json.getJSONArray("data").getJSONObject(0).getString("lastPr").toDouble()
        } catch (e: Exception) {
            android.util.Log.e("BOT_DEBUG", "❌ obtenerPrecioReal(${getApiSymbol(s)}): ${e.message}")
            0.0
        }
    }

    private suspend fun verificarIntegridadPosicion() {
        try {
            val apiSymbol = getApiSymbol(monedaConPosicion)
            val endpoint = "/api/v2/mix/position/single-position" +
                    "?symbol=${apiSymbol}" +
                    "&productType=${BitgetConfig.getProductType()}" +
                    "&marginCoin=${BitgetConfig.getMarginCoin()}"

            val resp = BitgetUtils.authenticatedGet(endpoint, apiKey, apiSecret, apiPassphrase)
            if (resp != null) {
                val json = JSONObject(resp)
                if (json.optString("code") == "00000") {
                    val data = json.optJSONArray("data")
                    var isRealmenteAbierta = false
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val total = data.getJSONObject(i).optString("total", "0").toDoubleOrNull() ?: 0.0
                            if (total > 0.0) {
                                isRealmenteAbierta = true
                                break
                            }
                        }
                    }
                    if (!isRealmenteAbierta && posicionAbierta) {
                        agregarLog("⚠️ Posición cerrada externamente (TP/SL de Bitget)")
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
        i.putExtra("RSI", lastRSI)
        i.putExtra("ATR", lastATR)

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
            val resp = BitgetUtils.authenticatedGet(
                "/api/v2/mix/account/accounts?productType=${BitgetConfig.getProductType()}",
                apiKey, apiSecret, apiPassphrase
            )
            val bal = JSONObject(resp ?: "{}")
                .getJSONArray("data")
                .getJSONObject(0)
                .getString("available")
                .toDouble()
            getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
                .edit()
                .putString("LAST_KNOWN_BALANCE", bal.toString())
                .apply()
            bal
        } catch (e: Exception) {
            null
        }
    }

    // ✅ V9: Parámetros corregidos — TP/SL coherentes con scalping en 15m
    private fun sincronizarAjustes() {
        BitgetConfig.loadFromPreferences(applicationContext)

        val p = getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        apiKey = p.getString("API_KEY", "") ?: ""
        apiSecret = p.getString("SECRET_KEY", "") ?: ""
        apiPassphrase = p.getString("API_PASSPHRASE", "") ?: ""

        // ✅ FIX: Parámetros V9 — targets alcanzables en 15m con 10x
        targetTP = V9TradingParams.TP_SCALPING           // 2.0%
        targetSL = V9TradingParams.SL_SCALPING           // 0.9%
        tsActivation = V9TradingParams.TRAILING_ACTIVATION  // 1.8%
        tsCallback = V9TradingParams.TRAILING_CALLBACK      // 0.8%

        leverage = 10.0
        riskPercent = p.getString("RISK_PERCENT", "5.0")?.toDoubleOrNull() ?: 5.0
        activeStrategy = p.getString("STRATEGY", "MODERADA") ?: "MODERADA"
        candleTimeframe = p.getString("TIMEFRAME_VAL", "15m") ?: "15m"

        candleIntervalMs = when (candleTimeframe) {
            "1m"  -> 60_000L
            "5m"  -> 300_000L
            "15m" -> 900_000L
            "30m" -> 1_800_000L
            "1h"  -> 3_600_000L
            else  -> 900_000L
        }

        activeSymbols.clear()
        if (p.getBoolean("COIN_BTC", true))  activeSymbols.add("BTCUSDT")
        if (p.getBoolean("COIN_ETH", false)) activeSymbols.add("ETHUSDT")
        if (p.getBoolean("COIN_SOL", false)) activeSymbols.add("SOLUSDT")
        if (p.getBoolean("COIN_XRP", false)) activeSymbols.add("XRPUSDT")
        if (activeSymbols.isEmpty()) activeSymbols.add("BTCUSDT")

        // ✅ V9: Crear instancia por símbolo
        ultimateStrategies.clear()
        activeSymbols.forEach { symbol ->
            ultimateStrategies[symbol] = SignalFusionV9Strategy(
                timeframe = candleTimeframe,
                mode = activeStrategy,
                useHTF = false  // Cambiar a true cuando implementes velas 1h
            )
        }
    }

    private fun agregarLog(m: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logLine = "[$timestamp] $m\n"
        logHistory.insert(0, logLine)
        sendBroadcast(Intent("ACTUALIZACION_TRADING").putExtra("LOG_MSG", m))
        android.util.Log.d("BOT_DEBUG", m)
    }

    private fun crearCanalesNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val m = getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_FOREGROUND,
                    "Motor V9",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun createForegroundNotification(title: String, content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        job?.cancel()
        try { wakeLock?.release() } catch (e: Exception) {}
        if (isReceiverRegistered) unregisterReceiver(manualCloseReceiver)
        super.onDestroy()
    }
}