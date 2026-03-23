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
 * TradingService V9.6 — MTFA Francotirador
 *
 * CAMBIO PRINCIPAL vs V9.5:
 *
 * Ahora el bot mantiene DOS historiales de velas por símbolo:
 * htfPreciosMap  → velas 1H  (para Capa 1 — dirección)
 * ltfPreciosMap  → velas 15m (para Capas 2/3 — entrada)
 *
 * Al construir MarketData se calculan los indicadores HTF y LTF
 * por separado y se pasan a los campos correspondientes.
 *
 * La estrategia V9.6 usa:
 * htfEmaTrend, htfEmaFast, htfEmaSlow, htfEmaMid, htfMacdHist, htfBbWidth
 * → para la Capa 1 (régimen)
 *
 * emaFast, emaSlow, emaMid, emaTrend, rsi, rsiMA, macdLine, macdSignal, macdHist
 * → para las Capas 2/3 (entrada)
 *
 * Las velas LTF se añaden cada 15 minutos.
 * Las velas HTF se añaden cada 1 hora.
 * El Order Flow se consulta en cada ciclo (cada 5s).
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
    private var leverage     = 5.0
    private var riskPercent  = 5.0
    private var activeStrategy = "MODERADA"

    // ✅ V9.6: dos timeframes fijos
    private val LTF = "15m"                   // entrada
    private val HTF = "1H"                    // dirección
    private val ltfIntervalMs = 900_000L      // 15 min
    private val htfIntervalMs = 3_600_000L    // 60 min

    private var lastLtfCandleTime = 0L
    private var lastHtfCandleTime = 0L

    private var maxPnLAlcanzado = 0.0
    private var initialBalance  = 0.0
    private var apiKey          = ""
    private var apiSecret       = ""
    private var apiPassphrase   = ""
    private var activeSymbols   = mutableListOf<String>()
    private var currentSymbolIndex = 0

    // ✅ V9.6: historial separado por temporalidad
    private val ltfPreciosMap = mutableMapOf<String, MutableList<Double>>()  // 15m
    private val htfPreciosMap = mutableMapOf<String, MutableList<Double>>()  // 1h

    private var ultimaOperacionTime = 0L
    private var consecutiveLosses   = 0
    private val winCooldown         = 20 * 60_000L    // 20 min tras win
    private val baseCooldown        = 20 * 60_000L    // 20 min tras apertura

    private var lastHeartbeat    = System.currentTimeMillis()
    private val heartbeatInterval = 300_000L
    private var errorCount       = 0

    private val failedSymbols      = mutableSetOf<String>()
    private val symbolFailCount    = mutableMapOf<String, Int>()
    private val ultimateStrategies = mutableMapOf<String, SignalFusionV9Strategy>()
    private val orderFlowAnalyzers = mutableMapOf<String, OrderFlowAnalyzer>()
    private val prevPriceMap       = mutableMapOf<String, Double>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        agregarLog("🔧 onCreate() llamado")
        crearCanalesNotificacion()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
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
                            agregarLog("⚠️ PÁNICO: Cerrando...")
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
            agregarLog("💰 Obteniendo balance (${if (BitgetConfig.isDemo()) "DEMO" else "REAL"})...")
            val saldo = obtenerBalanceBitget()
            if (saldo != null && saldo >= 0) {
                currentBalance = saldo
                if (initialBalance == 0.0) initialBalance = currentBalance
                isRunning = true

                agregarLog("✅ MOTOR V9.6 MTFA ${if (BitgetConfig.isDemo()) "DEMO" else "REAL"} | Balance: ${"%.2f".format(currentBalance)}")
                agregarLog("📊 TP: ${targetTP}% | SL: ${targetSL}% | R:R ${"%.1f".format(targetTP/targetSL)}:1")
                agregarLog("🛡️ Trailing: act ${tsActivation}% | cb ${tsCallback}%")
                agregarLog("🗺️ HTF: $HTF (dirección) | 🔬 LTF: $LTF (entrada) | ⚡ OF: tiempo real")
                agregarLog("🎯 Estrategia: $activeStrategy | Leverage: ${leverage.toInt()}x")
                agregarLog("🪙 Monedas: ${activeSymbols.joinToString(", ")}")

                val restante = ultimaOperacionTime - System.currentTimeMillis()
                if (restante > 0) agregarLog("⏳ Cooldown restaurado: ${restante / 60_000}min")

                cargarHistorialCerradas()
                activeSymbols.forEach {
                    setearLeverage(it, "LONG"); delay(300); setearLeverage(it, "SHORT")
                }
                iniciarCicloTrading()
            } else {
                agregarLog("❌ ERROR: No se pudo obtener balance")
                stopSelf()
            }
        }

        startForeground(1, createForegroundNotification(
            "SignalFusion V9.6",
            if (BitgetConfig.isDemo()) "DEMO HTF:$HTF LTF:$LTF + OF" else "REAL"
        ))
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false; job?.cancel(); persistirCooldown()
        try { wakeLock?.release() } catch (e: Exception) {}
        if (isReceiverRegistered) unregisterReceiver(manualCloseReceiver)
        super.onDestroy()
    }

    private fun persistirCooldown() {
        getSharedPreferences("BotConfig", Context.MODE_PRIVATE).edit()
            .putLong(PREF_ULTIMA_OP_TIME, ultimaOperacionTime)
            .putInt(PREF_CONSEC_LOSSES, consecutiveLosses).apply()
    }

    private fun restaurarCooldown() {
        val p = getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        ultimaOperacionTime = p.getLong(PREF_ULTIMA_OP_TIME, 0L)
        consecutiveLosses   = p.getInt(PREF_CONSEC_LOSSES, 0)
    }

    private fun getApiSymbol(s: String): String =
        if (BitgetConfig.isDemo()) "S" + s.replace("USDT", "SUSDT") else s

    // ─────────────────────────────────────────────
    // CICLO PRINCIPAL
    // ─────────────────────────────────────────────

    private fun iniciarCicloTrading() {
        job = CoroutineScope(Dispatchers.IO).launch {
            // Descargar historial inicial de ambas temporalidades
            activeSymbols.forEach { s ->
                descargarHistorial(s, LTF, ltfPreciosMap)
                delay(400)
                descargarHistorial(s, HTF, htfPreciosMap)
                delay(400)
            }

            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeat > heartbeatInterval) {
                        lastHeartbeat = now
                        agregarLog("💓 Heartbeat | Balance: ${"%.2f".format(currentBalance)}")
                    }

                    if (posicionAbierta) verificarIntegridadPosicion()

                    if (activeSymbols.isEmpty()) { actualizarEstadoUI("⚠️ Sin símbolos"); delay(10_000); continue }
                    if (currentSymbolIndex >= activeSymbols.size) currentSymbolIndex = 0

                    val symbol = if (posicionAbierta) monedaConPosicion else activeSymbols[currentSymbolIndex]
                    val precio = obtenerPrecioReal(symbol)

                    if (precio > 0) {
                        lastPrice = "%.2f".format(precio)

                        // ✅ V9.6: añadir velas a ambos historiales según su intervalo
                        val addLtf = now - lastLtfCandleTime >= ltfIntervalMs
                        val addHtf = now - lastHtfCandleTime >= htfIntervalMs

                        if (addLtf) { actualizarHistorial(symbol, precio, ltfPreciosMap); lastLtfCandleTime = now }
                        if (addHtf) { actualizarHistorial(symbol, precio, htfPreciosMap); lastHtfCandleTime = now }

                        val ltfHP = ltfPreciosMap[symbol] ?: mutableListOf()
                        val htfHP = htfPreciosMap[symbol] ?: mutableListOf()

                        if (ltfHP.size >= 50 && htfHP.size >= 50) {
                            val rsiRaw = try { Indicadores.calcularRSI(ltfHP, 14) } catch (e: Exception) { 0.0 }
                            val atrRaw = try { Indicadores.calcularATR(ltfHP, 14) } catch (e: Exception) { 0.0 }
                            val atrPct = if (precio > 0) (atrRaw / precio) * 100 else 0.0
                            lastRSI = "%.0f".format(rsiRaw)
                            lastATR = "%.2f".format(atrPct)

                            if (!posicionAbierta) {
                                ejecutarEstrategia(symbol, precio, ltfHP, htfHP, rsiRaw, atrPct, addLtf)
                            } else {
                                gestionarSalida(symbol, precio)
                            }
                        } else {
                            val ltfOk = ltfHP.size >= 50
                            val htfOk = htfHP.size >= 50
                            actualizarEstadoUI("📥 ${symbol} LTF:${ltfHP.size}/50 HTF:${htfHP.size}/50")
                            android.util.Log.d("BOT_DEBUG", "Calibrando $symbol | LTF:${ltfHP.size} HTF:${htfHP.size}")
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
    // EJECUTAR ESTRATEGIA MTFA
    // ─────────────────────────────────────────────

    private suspend fun ejecutarEstrategia(
        symbol: String,
        precio: Double,
        ltfHP: List<Double>,
        htfHP: List<Double>,
        rsi: Double,
        atrPct: Double,
        isNewLtfCandle: Boolean
    ) {
        if (System.currentTimeMillis() < ultimaOperacionTime) {
            val restMin = (ultimaOperacionTime - System.currentTimeMillis()) / 60_000
            actualizarEstadoUI("⏳ Cooldown ${restMin}min | RSI: $lastRSI | ATR: $lastATR%")
            return
        }

        if (atrPct < 0.2) {
            actualizarEstadoUI("⏸️ ATR bajo (${"%.2f".format(atrPct)}%) | $symbol")
            return
        }

        // ── Calcular indicadores LTF (15m) ──
        val ltfEmas  = Indicadores.calcularEMAs(ltfHP)
        val ltfBb    = Indicadores.calcularBollingerBands(ltfHP)
        val ltfMacd  = Indicadores.calcularMACD(ltfHP)
        val ltfRsiMA = try { Indicadores.calcularRSIMA(ltfHP, 14, 7) } catch (e: Exception) { rsi }

        // ── Calcular indicadores HTF (1H) ──
        val htfEmas = Indicadores.calcularEMAs(htfHP)
        val htfBb   = Indicadores.calcularBollingerBands(htfHP)
        val htfMacd = Indicadores.calcularMACD(htfHP)
        val htfBbWidth = if (htfBb.second > 0) (htfBb.first - htfBb.third) / htfBb.second else 0.0

        // ── Order Flow en tiempo real ──
        val orderFlow = fetchOrderFlow(symbol, precio)

        if (orderFlow != null) {
            android.util.Log.d("BOT_DEBUG",
                "📊 [$symbol] OF: ${orderFlow.dominance} CVD=${orderFlow.cvdTrend} " +
                        "buy=${(orderFlow.buyRatio*100).toInt()}% abs=${orderFlow.hasAbsorption}")
        }

        actualizarEstadoUI("$symbol | RSI(15m): $lastRSI | ATR: $lastATR%")

        // ── Construir MarketData con datos de ambas temporalidades ──
        val marketData = MarketData(
            // LTF (15m) → Capas 2 y 3
            price      = precio,
            high       = precio * 1.005,
            low        = precio * 0.995,
            rsi        = rsi,
            rsiMA      = ltfRsiMA,
            emaFast    = ltfEmas.fast,
            emaSlow    = ltfEmas.slow,
            emaMid     = ltfEmas.mid,
            emaTrend   = ltfEmas.trend,
            bbUpper    = ltfBb.first,
            bbMiddle   = ltfBb.second,
            bbLower    = ltfBb.third,
            macdLine   = ltfMacd.first,
            macdSignal = ltfMacd.second,
            macdHist   = ltfMacd.third,
            // HTF (1H) → Capa 1
            htfEmaFast  = htfEmas.fast,
            htfEmaSlow  = htfEmas.slow,
            htfEmaMid   = htfEmas.mid,
            htfEmaTrend = htfEmas.trend,
            htfMacdHist = htfMacd.third,
            htfBbWidth  = htfBbWidth,
            htfRsi      = rsi,   // aproximación — podríamos calcular RSI HTF si hace falta
            // Order Flow → Capa 4
            orderFlow  = orderFlow
        )

        val cerebro = ultimateStrategies[symbol] ?: return
        val result  = cerebro.evaluate(marketData, isNewLtfCandle)

        if (result.signal == "NEUTRAL") {
            android.util.Log.d("BOT_DEBUG", "🚫 [$symbol] ${result.rejectionReason}")
            actualizarEstadoUI("🚫 $symbol: ${result.rejectionReason.take(50)}")
        }

        if ((result.signal == "LONG" || result.signal == "SHORT") && !posicionAbierta) {
            agregarLog("✅✅✅ SEÑAL V9.6 MTFA: ${result.signal} en $symbol ✅✅✅")
            agregarLog("🗺️ Régimen 1H: ${result.regime} | Entrada 15m: ${result.confirmations}/3")
            agregarLog("🗳️ RSI=${result.familyVotes.rsiVote} EMA=${result.familyVotes.emaVote} MACD=${result.familyVotes.macdVote}")
            if (orderFlow != null) {
                agregarLog("📊 OF: ${orderFlow.dominance} CVD=${orderFlow.cvdTrend} Buy=${(orderFlow.buyRatio*100).toInt()}%")
            }
            posicionAbierta   = true
            monedaConPosicion = symbol
            tipoPosicion      = result.signal
            abrirTradeReal(symbol, precio, result.signal)
        }
    }

    // ─────────────────────────────────────────────
    // ORDER FLOW
    // ─────────────────────────────────────────────

    private suspend fun fetchOrderFlow(symbol: String, currentPrice: Double): OrderFlowData? = withContext(Dispatchers.IO) {
        try {
            val apiSym = getApiSymbol(symbol)
            val url = "https://api.bitget.com/api/v2/mix/market/fills" +
                    "?symbol=${apiSym}&productType=${BitgetConfig.getProductType()}&limit=500"
            val body = client.newCall(Request.Builder().url(url).build()).execute().body?.string()
                ?: return@withContext null
            val prevPrice = prevPriceMap[symbol] ?: currentPrice
            val data = orderFlowAnalyzers[symbol]?.analyze(body, currentPrice, prevPrice, symbol)
            prevPriceMap[symbol] = currentPrice
            data
        } catch (e: Exception) { null }
    }

    // ─────────────────────────────────────────────
    // GESTIÓN DE SALIDA
    // ─────────────────────────────────────────────

    private fun gestionarSalida(symbol: String, precio: Double) {
        val p   = lastPrice.replace(",", ".").toDoubleOrNull() ?: precio
        val pnl = calcularPnL(p, true)
        if (pnl > maxPnLAlcanzado) maxPnLAlcanzado = pnl
        actualizarEstadoUI("$symbol: ${"%.2f".format(pnl)}% | Max: ${"%.2f".format(maxPnLAlcanzado)}%")
        if (maxPnLAlcanzado >= tsActivation && (maxPnLAlcanzado - pnl) >= tsCallback) {
            agregarLog("🛡️ Trailing: Max ${"%.2f".format(maxPnLAlcanzado)}% → ${"%.2f".format(pnl)}%")
            cerrarTradeReal("Trailing Stop 🛡️")
        }
    }

    // ─────────────────────────────────────────────
    // APERTURA Y CIERRE DE TRADE
    // ─────────────────────────────────────────────

    private fun abrirTradeReal(symbol: String, precio: Double, tipo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            agregarLog("🎯 === ABRIENDO TRADE ${if (BitgetConfig.isDemo()) "DEMO" else "REAL"} ===")
            setearLeverage(symbol, tipo)

            val margen = when {
                currentBalance < 100.0  -> currentBalance * 0.05
                currentBalance < 2000.0 -> currentBalance * 0.04
                else                    -> currentBalance * (riskPercent / 100.0)
            }
            val size = (margen * leverage) / precio

            var ps = 1; var pp = 2
            when {
                symbol.contains("BTC") -> { ps = 3; pp = 1 }
                symbol.contains("ETH") -> { ps = 2; pp = 2 }
                symbol.contains("SOL") -> { ps = 1; pp = 3 }
                symbol.contains("XRP") -> { ps = 0; pp = 3 }
            }

            val sizeStr = String.format(Locale.US, "%.${ps}f", size)
            if ((sizeStr.toDoubleOrNull() ?: 0.0) <= 0.0 || currentBalance <= 1.0) {
                agregarLog("⚠️ Saldo insuficiente")
                posicionAbierta = false; monedaConPosicion = ""; return@launch
            }

            val dSL = precio * (targetSL / 100.0 / leverage)
            val dTP = precio * (targetTP / 100.0 / leverage)
            val slR = if (tipo == "LONG") precio - dSL else precio + dSL
            val tpR = if (tipo == "LONG") precio + dTP else precio - dTP
            val f   = Math.pow(10.0, pp.toDouble())
            val sl  = Math.round(slR * f) / f
            val tp  = Math.round(tpR * f) / f

            agregarLog("📐 Margen: ${"%.2f".format(margen)} USDT | TP: ${"%.${pp}f".format(tp)} | SL: ${"%.${pp}f".format(sl)}")

            val json = JSONObject().apply {
                put("symbol",                getApiSymbol(symbol))
                put("productType",           BitgetConfig.getProductType())
                put("marginCoin",            BitgetConfig.getMarginCoin())
                put("marginMode",            "crossed")
                put("side",                  if (tipo == "LONG") "buy" else "sell")
                put("tradeSide",             "open")
                put("orderType",             "market")
                put("size",                  sizeStr)
                put("presetStopLossPrice",   String.format(Locale.US, "%.${pp}f", sl))
                put("presetTakeProfitPrice", String.format(Locale.US, "%.${pp}f", tp))
            }

            val resp = BitgetUtils.authenticatedPost(
                "/api/v2/mix/order/place-order", json.toString(), apiKey, apiSecret, apiPassphrase)

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
                val err = if (resp != null) try { JSONObject(resp).optString("msg", resp) } catch (e: Exception) { resp } else "Timeout"
                agregarLog("❌ RECHAZO: $err")
                posicionAbierta = false; monedaConPosicion = ""
                ultimaOperacionTime = System.currentTimeMillis() + 120_000L
                persistirCooldown()
            }
        }
    }

    private fun cerrarTradeReal(motivo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            if (!posicionAbierta) return@launch
            val sym  = monedaConPosicion; val side = tipoPosicion
            val json = JSONObject().apply {
                put("symbol",      getApiSymbol(monedaConPosicion))
                put("productType", BitgetConfig.getProductType())
                put("marginCoin",  BitgetConfig.getMarginCoin())
                put("holdSide",    if (tipoPosicion == "LONG") "long" else "short")
            }
            val resp = BitgetUtils.authenticatedPost(
                "/api/v2/mix/order/close-positions", json.toString(), apiKey, apiSecret, apiPassphrase)
            if (resp != null && (resp.contains("\"code\":\"00000\"") || resp.contains("\"msg\":\"success\""))) {
                val pc  = lastPrice.replace(",", ".").toDoubleOrNull() ?: precioEntrada
                val pnl = calcularPnL(pc, true)
                ultimateStrategies[sym]?.reportResult(pnl > 0)
                if (pnl < 0) {
                    consecutiveLosses++
                    val cd = 60 + (consecutiveLosses * 30)
                    ultimaOperacionTime = System.currentTimeMillis() + (cd * 60_000L)
                    agregarLog("❌ LOSS #$consecutiveLosses → Cooldown: ${cd}min")
                } else {
                    consecutiveLosses = 0
                    ultimaOperacionTime = System.currentTimeMillis() + winCooldown
                    agregarLog("✅ WIN → Cooldown: ${winCooldown / 60_000}min")
                }
                persistirCooldown()
                posicionAbierta = false; monedaConPosicion = ""; maxPnLAlcanzado = 0.0
                obtenerBalanceBitget()?.let { currentBalance = it }
                agregarLog("🏁 CERRADO | $motivo | PnL: ${"%.2f".format(pnl)}%")
                actualizarEstadoUI("Escaneando...")
                TelegramNotifier.enviarSalida(applicationContext, sym, side, pnl, pc)
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

    private fun actualizarHistorial(symbol: String, precio: Double, map: MutableMap<String, MutableList<Double>>) {
        val lista = map[symbol] ?: return
        lista.add(precio)
        if (lista.size > 300) lista.removeAt(0)
    }

    private suspend fun descargarHistorial(s: String, tf: String, map: MutableMap<String, MutableList<Double>>) = withContext(Dispatchers.IO) {
        try {
            val apiSym = getApiSymbol(s)
            // ✅ CORRECCIÓN: Quitamos el .uppercase() para que Bitget no falle con "15M" (Meses)
            val url = "https://api.bitget.com/api/v2/mix/market/candles" +
                    "?symbol=${apiSym}&productType=${BitgetConfig.getProductType()}" +
                    "&granularity=${tf}&limit=200"
            agregarLog("📡 Velas $tf: $apiSym")
            val body = client.newCall(Request.Builder().url(url).build())
                .execute().body?.string() ?: run { markSymbolFailed(s, "Nula"); return@withContext }
            val json = JSONObject(body)
            if (json.optString("code") != "00000") {
                markSymbolFailed(s, json.optString("msg", "error")); return@withContext
            }
            val data = json.getJSONArray("data")
            val precios = mutableListOf<Double>()
            for (i in data.length() - 1 downTo 0)
                precios.add(data.getJSONArray(i).getString(4).toDouble())
            map[s] = precios
            agregarLog("✅ $apiSym $tf: ${precios.size} velas")
        } catch (e: Exception) { markSymbolFailed(s, e.message ?: "excepción") }
    }

    private suspend fun obtenerPrecioReal(s: String): Double = withContext(Dispatchers.IO) {
        if (s in failedSymbols) return@withContext 0.0
        try {
            val url  = "https://api.bitget.com/api/v2/mix/market/ticker" +
                    "?symbol=${getApiSymbol(s)}&productType=${BitgetConfig.getProductType()}"
            val body = client.newCall(Request.Builder().url(url).build()).execute().body?.string()
            val json = JSONObject(body ?: "{}")
            if (json.optString("code") != "00000") { markSymbolFailed(s, json.optString("msg")); return@withContext 0.0 }
            json.getJSONArray("data").getJSONObject(0).getString("lastPr").toDouble()
        } catch (e: Exception) { 0.0 }
    }

    private suspend fun verificarIntegridadPosicion() {
        try {
            val ep = "/api/v2/mix/position/single-position" +
                    "?symbol=${getApiSymbol(monedaConPosicion)}" +
                    "&productType=${BitgetConfig.getProductType()}&marginCoin=${BitgetConfig.getMarginCoin()}"
            val resp = BitgetUtils.authenticatedGet(ep, apiKey, apiSecret, apiPassphrase) ?: return
            val json = JSONObject(resp)
            if (json.optString("code") != "00000") return
            val data = json.optJSONArray("data")
            var open = false
            if (data != null) for (i in 0 until data.length()) {
                if ((data.getJSONObject(i).optString("total","0").toDoubleOrNull() ?: 0.0) > 0.0) { open = true; break }
            }
            if (!open && posicionAbierta) {
                agregarLog("⚠️ Posición cerrada externamente (TP/SL Bitget)")
                posicionAbierta = false; monedaConPosicion = ""
                ultimaOperacionTime = System.currentTimeMillis() + winCooldown
                persistirCooldown(); cargarHistorialCerradas()
            }
        } catch (e: Exception) {}
    }

    private suspend fun setearLeverage(symbol: String, tipo: String) = withContext(Dispatchers.IO) {
        try {
            BitgetUtils.authenticatedPost("/api/v2/mix/account/set-leverage",
                JSONObject().apply {
                    put("symbol", getApiSymbol(symbol)); put("productType", BitgetConfig.getProductType())
                    put("marginCoin", BitgetConfig.getMarginCoin()); put("leverage", leverage.toInt().toString())
                    put("holdSide", if (tipo == "LONG") "long" else "short")
                }.toString(), apiKey, apiSecret, apiPassphrase)
        } catch (e: Exception) {}
    }

    private suspend fun cargarHistorialCerradas() = withContext(Dispatchers.IO) {
        try {
            val resp = BitgetUtils.authenticatedGet(
                "/api/v2/mix/order/history-orders?productType=${BitgetConfig.getProductType()}&limit=100",
                apiKey, apiSecret, apiPassphrase) ?: return@withContext
            val json = JSONObject(resp)
            if (json.optString("code") != "00000") return@withContext
            val data = json.optJSONArray("data")
            var wins = 0; var losses = 0; var dailyPnl = 0.0
            val now = System.currentTimeMillis(); val day = 24L * 3600 * 1000L
            if (data != null) for (i in 0 until data.length()) {
                val o = data.getJSONObject(i)
                var pnl = o.optDouble("profit", 0.0)
                if (pnl == 0.0) pnl = o.optDouble("realizedPL", 0.0)
                if (pnl == 0.0) pnl = o.optDouble("netProfit", 0.0)
                if (pnl != 0.0) {
                    if (pnl > 0) wins++ else losses++
                    val t = o.optLong("uTime", o.optLong("cTime", now))
                    if (now - t <= day) dailyPnl += pnl
                }
            }
            val total = wins + losses
            val wr = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
            sendBroadcast(Intent("ACTUALIZACION_ESTADISTICAS").apply {
                putExtra("WIN_RATE", "${"%.1f".format(wr)}%"); putExtra("DAILY_PNL", dailyPnl) })
        } catch (e: Exception) { agregarLog("⚠️ Error Historial: ${e.message}") }
    }

    private suspend fun obtenerBalanceBitget(): Double? = withContext(Dispatchers.IO) {
        try {
            val resp = BitgetUtils.authenticatedGet(
                "/api/v2/mix/account/accounts?productType=${BitgetConfig.getProductType()}",
                apiKey, apiSecret, apiPassphrase)
            val bal = JSONObject(resp ?: "{}").getJSONArray("data").getJSONObject(0).getString("available").toDouble()
            getSharedPreferences("BotConfig", Context.MODE_PRIVATE).edit().putString("LAST_KNOWN_BALANCE", bal.toString()).apply()
            bal
        } catch (e: Exception) { null }
    }

    private fun markSymbolFailed(s: String, reason: String) {
        val count = (symbolFailCount[s] ?: 0) + 1; symbolFailCount[s] = count
        agregarLog("⚠️ ${getApiSymbol(s)} fallo #$count: $reason")
        if (count >= 2) {
            failedSymbols.add(s); activeSymbols.remove(s)
            ultimateStrategies.remove(s); orderFlowAnalyzers.remove(s)
            ltfPreciosMap.remove(s); htfPreciosMap.remove(s)
            agregarLog("🚫 ${getApiSymbol(s)} eliminado")
        }
    }

    private fun actualizarEstadoUI(msg: String) {
        sendBroadcast(Intent("ACTUALIZACION_TRADING").apply {
            putExtra("STATUS_MSG", msg); putExtra("BALANCE", currentBalance)
            putExtra("IS_TRADE_OPEN", posicionAbierta); putExtra("RSI", lastRSI); putExtra("ATR", lastATR)
            if (posicionAbierta) {
                val p = lastPrice.replace(",", ".").toDoubleOrNull() ?: precioEntrada
                putExtra("TRADE_SYMBOL", monedaConPosicion); putExtra("TRADE_TYPE", tipoPosicion)
                putExtra("TRADE_PNL", calcularPnL(p, true))
            }
        })
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
        activeStrategy = p.getString("STRATEGY", "MODERADA") ?: "MODERADA"

        activeSymbols.clear()
        if (p.getBoolean("COIN_BTC", true))  activeSymbols.add("BTCUSDT")
        if (p.getBoolean("COIN_ETH", false)) activeSymbols.add("ETHUSDT")
        if (p.getBoolean("COIN_SOL", false)) activeSymbols.add("SOLUSDT")
        if (activeSymbols.isEmpty()) activeSymbols.add("BTCUSDT")

        ultimateStrategies.clear(); orderFlowAnalyzers.clear()
        activeSymbols.forEach { s ->
            ultimateStrategies[s] = SignalFusionV9Strategy(
                ltfTimeframe = LTF, htfTimeframe = HTF, mode = activeStrategy)
            orderFlowAnalyzers[s] = OrderFlowAnalyzer()
        }
    }

    private fun agregarLog(m: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logHistory.insert(0, "[$ts] $m\n")
        sendBroadcast(Intent("ACTUALIZACION_TRADING").putExtra("LOG_MSG", m))
        android.util.Log.d("BOT_DEBUG", m)
    }

    private fun crearCanalesNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID_FOREGROUND, "Motor V9.6", NotificationManager.IMPORTANCE_LOW))
    }

    private fun createForegroundNotification(title: String, content: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle(title).setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pi).build()
    }
}