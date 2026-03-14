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

/**
 * TradingService V9.4 — Swing 1H — Opera 24h
 *
 * CONFIGURACIÓN:
 *   Timeframe : 1h
 *   Leverage  : 5x
 *   Estrategia: MODERADA (2/3 familias)
 *   TP        : 4.0% | SL: 2.0% | R:R 2:1
 *   Trailing  : activación 3.0% | callback 1.0%
 *   Cooldown  : 60 min base (escala con pérdidas)
 *   Horario   : 24h — filtro por ATR mínimo 0.3%
 *   Monedas   : BTC por defecto (ETH opcional)
 *
 * FIXES INCLUIDOS:
 *   - Cooldown persistente en SharedPreferences (sobrevive reinicios)
 *   - Fix coma locale español en lastPrice (toDoubleOrNull)
 *   - Cooldown escalado: 1 pérdida=90min, 2=150min, 3=210min
 *   - Sin filtro horario — ATR es el único guardián de mercado dormido
 */

class TradingService : Service() {

    companion object {
        var isRunning         = false
        var currentBalance    = 0.0
        var logHistory        = StringBuilder()
        var posicionAbierta   = false
        var monedaConPosicion = ""
        var tipoPosicion      = ""
        var precioEntrada     = 0.0
        var lastPrice         = "0.0"
        var lastRSI           = "--"
        var lastATR           = "--"
        var currentStatus     = "Iniciando..."

        private const val PREF_ULTIMA_OP_TIME = "ULTIMA_OP_TIME"
        private const val PREF_CONSEC_LOSSES  = "CONSECUTIVE_LOSSES"
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
    private lateinit var manualCloseReceiver: BroadcastReceiver
    private var isReceiverRegistered = false

    private var targetTP     = V9TradingParams.TP_SCALPING
    private var targetSL     = V9TradingParams.SL_SCALPING
    private var tsActivation = V9TradingParams.TRAILING_ACTIVATION
    private var tsCallback   = V9TradingParams.TRAILING_CALLBACK

    private var leverage         = 5.0
    private var riskPercent      = 5.0
    private var activeStrategy   = "MODERADA"
    private var candleTimeframe  = "1h"
    private var candleIntervalMs = 3_600_000L

    private var lastCandleTime  = 0L
    private var maxPnLAlcanzado = 0.0
    private var initialBalance  = 0.0
    private var apiKey          = ""
    private var apiSecret       = ""
    private var apiPassphrase   = ""
    private var activeSymbols   = mutableListOf<String>()
    private var currentSymbolIndex = 0
    private val historialPreciosMap = mutableMapOf<String, MutableList<Double>>()

    private var ultimaOperacionTime = 0L
    private var consecutiveLosses   = 0
    private val baseCooldown        = 3_600_000L   // 60 min

    private var lastHeartbeat    = System.currentTimeMillis()
    private val heartbeatInterval = 300_000L
    private var errorCount       = 0

    private val failedSymbols      = mutableSetOf<String>()
    private val symbolFailCount    = mutableMapOf<String, Int>()
    private val ultimateStrategies = mutableMapOf<String, SignalFusionV9Strategy>()
    private val lastHpSizeMap      = mutableMapOf<String, Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────
    // CICLO DE VIDA
    // ─────────────────────────────────────────────

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
                when (intent?.action) {
                    "FORZAR_CIERRE_MANUAL" -> {
                        if (posicionAbierta) {
                            agregarLog("⚠️ PÁNICO: Ejecutando cierre...")
                            CoroutineScope(Dispatchers.IO).launch { cerrarTradeReal("Manual Panic") }
                        } else actualizarEstadoUI("Limpiando UI...")
                    }
                    "SOLICITAR_REFRESH_UI", "SOLICITAR_REFRESH_BALANCE" -> {
                        CoroutineScope(Dispatchers.IO).launch {
                            obtenerBalanceBitget()?.let { currentBalance = it }
                            actualizarEstadoUI("Sincronizado")
                            cargarHistorialCerradas()
                        }
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
        restaurarCooldown()

        CoroutineScope(Dispatchers.IO).launch {
            agregarLog("💰 Obteniendo balance Bitget (${if (BitgetConfig.isDemo()) "DEMO" else "REAL"})...")
            val saldo = obtenerBalanceBitget()
            if (saldo != null && saldo >= 0) {
                currentBalance = saldo
                if (initialBalance == 0.0) initialBalance = currentBalance
                isRunning = true

                agregarLog("✅ MOTOR V9.4 ${if (BitgetConfig.isDemo()) "DEMO" else "REAL"} | Balance: ${"%.2f".format(currentBalance)}")
                agregarLog("📊 TP: ${"%.1f".format(targetTP)}% | SL: ${"%.1f".format(targetSL)}% | R:R ${"%.1f".format(targetTP / targetSL)}:1")
                agregarLog("🛡️ Trailing: act ${tsActivation}% | cb ${tsCallback}%")
                agregarLog("🎯 Estrategia: $activeStrategy | Timeframe: $candleTimeframe | Leverage: ${leverage.toInt()}x")
                agregarLog("🪙 Monedas: ${activeSymbols.joinToString(", ")}")
                agregarLog("🕐 Opera 24h | Filtro ATR activo (mín 0.3%)")

                val restante = ultimaOperacionTime - System.currentTimeMillis()
                if (restante > 0) agregarLog("⏳ Cooldown restaurado: ${restante / 60_000}min restantes")

                cargarHistorialCerradas()
                activeSymbols.forEach {
                    agregarLog("🔧 Leverage ${leverage.toInt()}x → $it")
                    setearLeverage(it, "LONG")
                    delay(300)
                    setearLeverage(it, "SHORT")
                }
                iniciarCicloTrading()
            } else {
                agregarLog("❌ ERROR: No se pudo obtener balance. Revisa API Keys")
                stopSelf()
            }
        }

        startForeground(1, createForegroundNotification(
            "SignalFusion V9.4",
            if (BitgetConfig.isDemo()) "DEMO $candleTimeframe $activeStrategy ${leverage.toInt()}x" else "REAL"
        ))
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        job?.cancel()
        persistirCooldown()
        try { wakeLock?.release() } catch (e: Exception) {}
        if (isReceiverRegistered) unregisterReceiver(manualCloseReceiver)
        super.onDestroy()
    }

    // ─────────────────────────────────────────────
    // PERSISTENCIA DE COOLDOWN
    // ─────────────────────────────────────────────

    private fun persistirCooldown() {
        getSharedPreferences("BotConfig", Context.MODE_PRIVATE).edit()
            .putLong(PREF_ULTIMA_OP_TIME, ultimaOperacionTime)
            .putInt(PREF_CONSEC_LOSSES, consecutiveLosses)
            .apply()
    }

    private fun restaurarCooldown() {
        val p = getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        ultimaOperacionTime = p.getLong(PREF_ULTIMA_OP_TIME, 0L)
        consecutiveLosses   = p.getInt(PREF_CONSEC_LOSSES, 0)
    }

    // ─────────────────────────────────────────────
    // CICLO PRINCIPAL DE TRADING
    // ─────────────────────────────────────────────

    private fun iniciarCicloTrading() {
        job = CoroutineScope(Dispatchers.IO).launch {
            activeSymbols.forEach { descargarHistorialInicial(it); delay(500) }

            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeat > heartbeatInterval) {
                        lastHeartbeat = now
                        agregarLog("💓 Heartbeat | Balance: ${"%.2f".format(currentBalance)}")
                    }

                    if (posicionAbierta) verificarIntegridadPosicion()

                    if (activeSymbols.isEmpty()) {
                        actualizarEstadoUI("⚠️ Sin símbolos activos")
                        delay(10_000); continue
                    }
                    if (currentSymbolIndex >= activeSymbols.size) currentSymbolIndex = 0

                    val symbol = if (posicionAbierta) monedaConPosicion else activeSymbols[currentSymbolIndex]
                    val precio = obtenerPrecioReal(symbol)

                    if (precio > 0) {
                        lastPrice = "%.2f".format(precio)

                        val shouldAddCandle = System.currentTimeMillis() - lastCandleTime >= candleIntervalMs
                        if (shouldAddCandle) {
                            actualizarDatosVela(symbol, precio)
                            lastCandleTime = System.currentTimeMillis()
                        }

                        val hP = historialPreciosMap[symbol] ?: mutableListOf()
                        if (hP.size >= 50) {
                            val rsiRaw = try { Indicadores.calcularRSI(hP, 14) } catch (e: Exception) { 0.0 }
                            val atrRaw = try { Indicadores.calcularATR(hP, 14) } catch (e: Exception) { 0.0 }
                            val atrPct = if (precio > 0) (atrRaw / precio) * 100 else 0.0
                            lastRSI = "%.0f".format(rsiRaw)
                            lastATR = "%.2f".format(atrPct)

                            if (!posicionAbierta) {
                                ejecutarEstrategiaUltimate(symbol, precio, hP, rsiRaw, atrPct, shouldAddCandle)
                            } else {
                                gestionarSalida(symbol, precio)
                            }
                        } else {
                            android.util.Log.d("BOT_DEBUG", "📥 Calibrando $symbol: ${hP.size}/50")
                            actualizarEstadoUI("📥 Calibrando $symbol (${hP.size}/50)")
                        }
                    }

                    if (!posicionAbierta && activeSymbols.isNotEmpty())
                        currentSymbolIndex = (currentSymbolIndex + 1) % activeSymbols.size

                    errorCount = 0
                } catch (e: Exception) {
                    errorCount++
                    if (errorCount > 3) { stopSelf(); break } else delay(5000)
                }

                delay(if (posicionAbierta) 3000 else 5000)
            }
        }
    }

    // ─────────────────────────────────────────────
    // ESTRATEGIA — EVALUACIÓN Y APERTURA
    // ─────────────────────────────────────────────

    private fun ejecutarEstrategiaUltimate(
        symbol: String,
        precio: Double,
        hP: List<Double>,
        rsi: Double,
        atrPercent: Double,
        isNewCandle: Boolean
    ) {
        // Cooldown entre trades
        if (System.currentTimeMillis() < ultimaOperacionTime) {
            val restMin = (ultimaOperacionTime - System.currentTimeMillis()) / 60_000
            actualizarEstadoUI("⏳ Cooldown ${restMin}min | RSI: $lastRSI | ATR: $lastATR%")
            return
        }

        // Filtro de volatilidad mínima — único guardián de mercado dormido
        if (atrPercent < 0.3) {
            actualizarEstadoUI("⏸️ ATR bajo (${"%.2f".format(atrPercent)}%) | $symbol — mercado sin movimiento")
            return
        }

        val emas  = Indicadores.calcularEMAs(hP)
        val bb    = Indicadores.calcularBollingerBands(hP)
        val macd  = Indicadores.calcularMACD(hP)
        val rsiMA = try { Indicadores.calcularRSIMA(hP, 14, 7) } catch (e: Exception) { rsi }

        actualizarEstadoUI("Analizando $symbol | RSI: $lastRSI | ATR: $lastATR%")

        val marketData = MarketData(
            price      = precio,
            high       = precio * 1.005,
            low        = precio * 0.995,
            rsi        = rsi,
            rsiMA      = rsiMA,
            emaFast    = emas.fast,
            emaSlow    = emas.slow,
            emaMid     = emas.mid,
            emaTrend   = emas.trend,
            bbUpper    = bb.first,
            bbMiddle   = bb.second,
            bbLower    = bb.third,
            macdLine   = macd.first,
            macdSignal = macd.second,
            macdHist   = macd.third
        )

        val cerebro = ultimateStrategies[symbol] ?: return
        val result  = cerebro.evaluate(marketData, isNewCandle)

        if (result.signal == "NEUTRAL") {
            android.util.Log.d("BOT_DEBUG", "🚫 [$symbol] ${result.rejectionReason}")
            actualizarEstadoUI("🚫 $symbol: ${result.rejectionReason.take(50)}")
        }

        if ((result.signal == "LONG" || result.signal == "SHORT") && !posicionAbierta) {
            agregarLog("✅✅✅ SEÑAL V9.4: ${result.signal} en $symbol ✅✅✅")
            agregarLog("📊 Régimen: ${result.regime} | Confirmaciones: ${result.confirmations}/3")
            agregarLog("🗳️ RSI=${result.familyVotes.rsiVote} EMA=${result.familyVotes.emaVote} MACD=${result.familyVotes.macdVote}")
            posicionAbierta   = true
            monedaConPosicion = symbol
            tipoPosicion      = result.signal
            abrirTradeReal(symbol, precio, result.signal)
        }
    }

    // ─────────────────────────────────────────────
    // GESTIÓN DE SALIDA (TRAILING)
    // ─────────────────────────────────────────────

    private fun gestionarSalida(symbol: String, precio: Double) {
        val precioActual = lastPrice.replace(",", ".").toDoubleOrNull() ?: precio
        val pnlNeto      = calcularPnL(precioActual, true)
        if (pnlNeto > maxPnLAlcanzado) maxPnLAlcanzado = pnlNeto
        actualizarEstadoUI("$symbol: ${"%.2f".format(pnlNeto)}% | Max: ${"%.2f".format(maxPnLAlcanzado)}%")
        if (maxPnLAlcanzado >= tsActivation && (maxPnLAlcanzado - pnlNeto) >= tsCallback) {
            agregarLog("🛡️ Trailing: Max ${"%.2f".format(maxPnLAlcanzado)}% → ${"%.2f".format(pnlNeto)}%")
            cerrarTradeReal("Trailing Stop 🛡️")
        }
    }

    // ─────────────────────────────────────────────
    // APERTURA DE TRADE
    // ─────────────────────────────────────────────

    private fun abrirTradeReal(symbol: String, precio: Double, tipo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            agregarLog("🎯 === ABRIENDO TRADE ${if (BitgetConfig.isDemo()) "DEMO" else "REAL"} ===")
            setearLeverage(symbol, tipo)

            val margenDeseado = when {
                currentBalance < 100.0  -> currentBalance * 0.05
                currentBalance < 500.0  -> currentBalance * 0.05
                currentBalance < 2000.0 -> currentBalance * 0.04
                else                    -> currentBalance * (riskPercent / 100.0)
            }
            val sizeAmount = (margenDeseado * leverage) / precio

            var precisionSize  = 1
            var precisionPrice = 2
            when {
                symbol.contains("BTC") -> { precisionSize = 3; precisionPrice = 1 }
                symbol.contains("ETH") -> { precisionSize = 2; precisionPrice = 2 }
                symbol.contains("SOL") -> { precisionSize = 1; precisionPrice = 3 }
                symbol.contains("XRP") -> { precisionSize = 0; precisionPrice = 3 }
            }

            val sizeStr   = String.format(Locale.US, "%.${precisionSize}f", sizeAmount)
            val sizeFinal = sizeStr.toDoubleOrNull() ?: 0.0

            if (sizeFinal <= 0.0 || currentBalance <= 1.0) {
                agregarLog("⚠️ Saldo insuficiente")
                posicionAbierta = false; monedaConPosicion = ""; return@launch
            }

            val distanciaSL = precio * (targetSL / 100.0 / leverage)
            val distanciaTP = precio * (targetTP / 100.0 / leverage)
            val slPriceRaw  = if (tipo == "LONG") precio - distanciaSL else precio + distanciaSL
            val tpPriceRaw  = if (tipo == "LONG") precio + distanciaTP else precio - distanciaTP
            val factor      = Math.pow(10.0, precisionPrice.toDouble())
            val slPrice     = Math.round(slPriceRaw * factor) / factor
            val tpPrice     = Math.round(tpPriceRaw * factor) / factor

            agregarLog("📐 Tamaño: $sizeStr | Margen: ${"%.2f".format(margenDeseado)} USDT | Lev: ${leverage.toInt()}x")
            agregarLog("📤 Orden | TP: ${"%.${precisionPrice}f".format(tpPrice)} (+${targetTP}%) | SL: ${"%.${precisionPrice}f".format(slPrice)} (-${targetSL}%)")

            val apiSymbol = getApiSymbol(symbol)
            val json = JSONObject().apply {
                put("symbol",                apiSymbol)
                put("productType",           BitgetConfig.getProductType())
                put("marginCoin",            BitgetConfig.getMarginCoin())
                put("marginMode",            "crossed")
                put("side",                  if (tipo == "LONG") "buy" else "sell")
                put("tradeSide",             "open")
                put("orderType",             "market")
                put("size",                  sizeStr)
                put("presetStopLossPrice",   String.format(Locale.US, "%.${precisionPrice}f", slPrice))
                put("presetTakeProfitPrice", String.format(Locale.US, "%.${precisionPrice}f", tpPrice))
            }

            val resp = BitgetUtils.authenticatedPost(
                "/api/v2/mix/order/place-order",
                json.toString(), apiKey, apiSecret, apiPassphrase
            )

            if (resp != null && (resp.contains("\"code\":\"00000\"") || resp.contains("\"msg\":\"success\""))) {
                delay(1500)
                precioEntrada       = precio
                maxPnLAlcanzado     = -0.1
                ultimaOperacionTime = System.currentTimeMillis() + baseCooldown
                persistirCooldown()
                agregarLog("✅✅✅ ORDEN EJECUTADA ✅✅✅")
                actualizarEstadoUI("🚀 POSICIÓN ACTIVA")
                TelegramNotifier.enviarEntrada(applicationContext, symbol, tipo, precio)
            } else {
                val errorMsg = if (resp != null) {
                    try { JSONObject(resp).optString("msg", resp) } catch (e: Exception) { resp }
                } else "Timeout/Error red"
                agregarLog("❌ RECHAZO BITGET: $errorMsg")
                posicionAbierta = false; monedaConPosicion = ""
                ultimaOperacionTime = System.currentTimeMillis() + 120_000L
                persistirCooldown()
            }
        }
    }

    // ─────────────────────────────────────────────
    // CIERRE DE TRADE
    // ─────────────────────────────────────────────

    private fun cerrarTradeReal(motivo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            if (!posicionAbierta) return@launch
            val symTemp  = monedaConPosicion
            val sideTemp = tipoPosicion

            val json = JSONObject().apply {
                put("symbol",      getApiSymbol(monedaConPosicion))
                put("productType", BitgetConfig.getProductType())
                put("marginCoin",  BitgetConfig.getMarginCoin())
                put("holdSide",    if (tipoPosicion == "LONG") "long" else "short")
            }

            val resp = BitgetUtils.authenticatedPost(
                "/api/v2/mix/order/close-positions",
                json.toString(), apiKey, apiSecret, apiPassphrase
            )

            if (resp != null && (resp.contains("\"code\":\"00000\"") || resp.contains("\"msg\":\"success\""))) {
                val precioCierre = lastPrice.replace(",", ".").toDoubleOrNull() ?: precioEntrada
                val pnlFinal     = calcularPnL(precioCierre, true)

                ultimateStrategies[symTemp]?.reportResult(pnlFinal > 0)

                if (pnlFinal < 0) {
                    consecutiveLosses++
                    // Cooldown escalado: 1 pérdida=90min, 2=150min, 3=210min
                    val cooldownMin = 60 + (consecutiveLosses * 30)
                    ultimaOperacionTime = System.currentTimeMillis() + (cooldownMin * 60_000L)
                    agregarLog("❌ LOSS #$consecutiveLosses → Cooldown: ${cooldownMin}min")
                } else {
                    consecutiveLosses = 0
                    ultimaOperacionTime = System.currentTimeMillis() + baseCooldown
                    agregarLog("✅ WIN → Cooldown base: ${baseCooldown / 60_000}min")
                }

                persistirCooldown()
                posicionAbierta   = false
                monedaConPosicion = ""
                maxPnLAlcanzado   = 0.0
                obtenerBalanceBitget()?.let { currentBalance = it }
                agregarLog("🏁 CERRADO | $motivo | PnL: ${"%.2f".format(pnlFinal)}%")
                actualizarEstadoUI("Escaneando...")
                TelegramNotifier.enviarSalida(applicationContext, symTemp, sideTemp, pnlFinal, precioCierre)
                cargarHistorialCerradas()
            }
        }
    }

    // ─────────────────────────────────────────────
    // UTILIDADES
    // ─────────────────────────────────────────────

    private fun calcularPnL(actual: Double, conFees: Boolean): Double {
        if (precioEntrada <= 0) return 0.0
        val diff  = if (tipoPosicion == "LONG") actual - precioEntrada else precioEntrada - actual
        val bruto = (diff / precioEntrada) * 100 * leverage
        return if (conFees) bruto - (0.12 * leverage) else bruto
    }

    private fun actualizarDatosVela(symbol: String, precio: Double) {
        val lista = historialPreciosMap[symbol] ?: return
        lista.add(precio)
        if (lista.size > 300) lista.removeAt(0)
    }

    private fun getApiSymbol(baseSymbol: String): String =
        if (BitgetConfig.isDemo()) "S" + baseSymbol.replace("USDT", "SUSDT") else baseSymbol

    // ─────────────────────────────────────────────
    // API — HISTORIAL, PRECIO, POSICIÓN
    // ─────────────────────────────────────────────

    private suspend fun cargarHistorialCerradas() = withContext(Dispatchers.IO) {
        try {
            val endpoint = "/api/v2/mix/order/history-orders?productType=${BitgetConfig.getProductType()}&limit=100"
            val resp = BitgetUtils.authenticatedGet(endpoint, apiKey, apiSecret, apiPassphrase)
            if (resp != null) {
                val json = JSONObject(resp)
                if (json.optString("code") == "00000") {
                    val data = json.optJSONArray("data")
                    var wins = 0; var losses = 0; var dailyPnl = 0.0
                    val now     = System.currentTimeMillis()
                    val unDiaMs = 24L * 3600 * 1000L
                    if (data != null) {
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
                    }
                    val total = wins + losses
                    val wr    = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
                    sendBroadcast(Intent("ACTUALIZACION_ESTADISTICAS").apply {
                        putExtra("WIN_RATE",  "${"%.1f".format(wr)}%")
                        putExtra("DAILY_PNL", dailyPnl)
                    })
                }
            }
        } catch (e: Exception) { agregarLog("⚠️ Error Historial: ${e.message}") }
    }

    private suspend fun descargarHistorialInicial(s: String) = withContext(Dispatchers.IO) {
        try {
            val apiSym = getApiSymbol(s)
            val url = "https://api.bitget.com/api/v2/mix/market/candles" +
                    "?symbol=${apiSym}&productType=${BitgetConfig.getProductType()}" +
                    "&granularity=${candleTimeframe.uppercase()}&limit=200"
            agregarLog("📡 Descargando velas $candleTimeframe: $apiSym")
            val body = client.newCall(Request.Builder().url(url).build())
                .execute().body?.string() ?: run { markSymbolFailed(s, "Nula"); return@withContext }
            android.util.Log.d("BOT_DEBUG", "Candles [$apiSym]: ${body.take(120)}")
            val json = JSONObject(body)
            if (json.optString("code") != "00000") {
                markSymbolFailed(s, json.optString("msg", "error")); return@withContext
            }
            val data    = json.getJSONArray("data")
            val precios = mutableListOf<Double>()
            for (i in data.length() - 1 downTo 0)
                precios.add(data.getJSONArray(i).getString(4).toDouble())
            historialPreciosMap[s] = precios
            lastHpSizeMap[s]       = precios.size
            agregarLog("✅ $apiSym: ${precios.size} velas cargadas")
        } catch (e: Exception) { markSymbolFailed(s, e.message ?: "excepción") }
    }

    private suspend fun obtenerPrecioReal(s: String): Double = withContext(Dispatchers.IO) {
        if (s in failedSymbols) return@withContext 0.0
        try {
            val url  = "https://api.bitget.com/api/v2/mix/market/ticker" +
                    "?symbol=${getApiSymbol(s)}&productType=${BitgetConfig.getProductType()}"
            val body = client.newCall(Request.Builder().url(url).build()).execute().body?.string()
            val json = JSONObject(body ?: "{}")
            if (json.optString("code") != "00000") {
                markSymbolFailed(s, json.optString("msg", "ticker error"))
                return@withContext 0.0
            }
            json.getJSONArray("data").getJSONObject(0).getString("lastPr").toDouble()
        } catch (e: Exception) { 0.0 }
    }

    private suspend fun verificarIntegridadPosicion() {
        try {
            val endpoint = "/api/v2/mix/position/single-position" +
                    "?symbol=${getApiSymbol(monedaConPosicion)}" +
                    "&productType=${BitgetConfig.getProductType()}" +
                    "&marginCoin=${BitgetConfig.getMarginCoin()}"
            val resp = BitgetUtils.authenticatedGet(endpoint, apiKey, apiSecret, apiPassphrase)
            if (resp != null) {
                val json = JSONObject(resp)
                if (json.optString("code") == "00000") {
                    val data    = json.optJSONArray("data")
                    var abierta = false
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            if ((data.getJSONObject(i).optString("total", "0").toDoubleOrNull() ?: 0.0) > 0.0) {
                                abierta = true; break
                            }
                        }
                    }
                    if (!abierta && posicionAbierta) {
                        agregarLog("⚠️ Posición cerrada externamente (TP/SL Bitget)")
                        posicionAbierta   = false
                        monedaConPosicion = ""
                        ultimaOperacionTime = System.currentTimeMillis() + baseCooldown
                        persistirCooldown()
                        cargarHistorialCerradas()
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private suspend fun setearLeverage(symbol: String, tipo: String) = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("symbol",      getApiSymbol(symbol))
                put("productType", BitgetConfig.getProductType())
                put("marginCoin",  BitgetConfig.getMarginCoin())
                put("leverage",    leverage.toInt().toString())
                put("holdSide",    if (tipo == "LONG") "long" else "short")
            }
            BitgetUtils.authenticatedPost(
                "/api/v2/mix/account/set-leverage",
                json.toString(), apiKey, apiSecret, apiPassphrase
            )
        } catch (e: Exception) {}
    }

    private suspend fun obtenerBalanceBitget(): Double? = withContext(Dispatchers.IO) {
        try {
            val resp = BitgetUtils.authenticatedGet(
                "/api/v2/mix/account/accounts?productType=${BitgetConfig.getProductType()}",
                apiKey, apiSecret, apiPassphrase
            )
            val bal = JSONObject(resp ?: "{}")
                .getJSONArray("data").getJSONObject(0)
                .getString("available").toDouble()
            getSharedPreferences("BotConfig", Context.MODE_PRIVATE).edit()
                .putString("LAST_KNOWN_BALANCE", bal.toString()).apply()
            bal
        } catch (e: Exception) { null }
    }

    private fun markSymbolFailed(s: String, reason: String) {
        val count = (symbolFailCount[s] ?: 0) + 1
        symbolFailCount[s] = count
        agregarLog("⚠️ ${getApiSymbol(s)} fallo #$count: $reason")
        if (count >= 2) {
            failedSymbols.add(s)
            activeSymbols.remove(s)
            ultimateStrategies.remove(s)
            agregarLog("🚫 ${getApiSymbol(s)} eliminado de la lista")
        }
    }

    // ─────────────────────────────────────────────
    // UI Y CONFIGURACIÓN
    // ─────────────────────────────────────────────

    private fun actualizarEstadoUI(mensaje: String) {
        val i = Intent("ACTUALIZACION_TRADING").apply {
            putExtra("STATUS_MSG",    mensaje)
            putExtra("BALANCE",       currentBalance)
            putExtra("IS_TRADE_OPEN", posicionAbierta)
            putExtra("RSI",           lastRSI)
            putExtra("ATR",           lastATR)
            if (posicionAbierta) {
                val precio = lastPrice.replace(",", ".").toDoubleOrNull() ?: precioEntrada
                val pnl    = calcularPnL(precio, true)
                putExtra("TRADE_SYMBOL", monedaConPosicion)
                putExtra("TRADE_TYPE",   tipoPosicion)
                putExtra("TRADE_PNL",    pnl)
            }
        }
        sendBroadcast(i)
    }

    private fun sincronizarAjustes() {
        BitgetConfig.loadFromPreferences(applicationContext)
        val p = getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        apiKey        = p.getString("API_KEY",        "") ?: ""
        apiSecret     = p.getString("SECRET_KEY",     "") ?: ""
        apiPassphrase = p.getString("API_PASSPHRASE", "") ?: ""

        targetTP     = V9TradingParams.TP_SCALPING
        targetSL     = V9TradingParams.SL_SCALPING
        tsActivation = V9TradingParams.TRAILING_ACTIVATION
        tsCallback   = V9TradingParams.TRAILING_CALLBACK
        leverage     = 5.0
        riskPercent  = p.getString("RISK_PERCENT", "5.0")?.toDoubleOrNull() ?: 5.0

        activeStrategy  = p.getString("STRATEGY",      "MODERADA") ?: "MODERADA"
        candleTimeframe = p.getString("TIMEFRAME_VAL", "1h")       ?: "1h"
        candleIntervalMs = when (candleTimeframe) {
            "1m"  -> 60_000L
            "5m"  -> 300_000L
            "15m" -> 900_000L
            "30m" -> 1_800_000L
            "1h"  -> 3_600_000L
            "4h"  -> 14_400_000L
            else  -> 3_600_000L
        }

        activeSymbols.clear()
        if (p.getBoolean("COIN_BTC", true))  activeSymbols.add("BTCUSDT")
        if (p.getBoolean("COIN_ETH", false)) activeSymbols.add("ETHUSDT")
        if (p.getBoolean("COIN_SOL", false)) activeSymbols.add("SOLUSDT")
        if (activeSymbols.isEmpty()) activeSymbols.add("BTCUSDT")

        ultimateStrategies.clear()
        activeSymbols.forEach { symbol ->
            ultimateStrategies[symbol] = SignalFusionV9Strategy(
                timeframe = candleTimeframe,
                mode      = activeStrategy,
                useHTF    = false
            )
        }
    }

    private fun agregarLog(m: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logHistory.insert(0, "[$ts] $m\n")
        sendBroadcast(Intent("ACTUALIZACION_TRADING").putExtra("LOG_MSG", m))
        android.util.Log.d("BOT_DEBUG", m)
    }

    private fun crearCanalesNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID_FOREGROUND, "Motor V9.4", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun createForegroundNotification(title: String, content: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pi)
            .build()
    }
}