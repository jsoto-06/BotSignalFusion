package com.example.signalfusion

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
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
        var currentBalance = 1000.0

        // Historial de Logs
        var logHistory = StringBuilder()

        // Caché UI
        var lastPrice = "Conectando..."
        var lastRSI = "--"
        var lastVol = "--"
        var lastTrend = "--"
        var currentStatus = "Iniciando..."

        // Estadísticas
        var totalTrades = 0
        var tradesGanados = 0
        var tradesPerdidos = 0
    }

    private val client = OkHttpClient()
    private var job: Job? = null

    // Canales
    private val CHANNEL_ID_FOREGROUND = "SignalFusionChannel"
    private val CHANNEL_ID_ALERTS = "SignalFusionAlerts"

    // 🆕 FASE 2: Receptor del Botón de Pánico
    private var manualCloseReceiver: BroadcastReceiver? = null

    // Configuración Base
    private var targetTP = 2.0
    private var targetSL = 1.5
    private var leverage = 5.0
    private var isTurbo = false
    private var tamanoPosicionUSDT = 0.0
    private var activeStrategy = "MODERADA"

    // Multi-Moneda
    private var activeSymbols = mutableListOf<String>()
    private var currentSymbolIndex = 0
    private var symbolBeingScanned = "BTCUSDT"

    // Parámetros Fase 1
    private var timeframe = "1m"
    private var tsActivation = 1.3
    private var tsRetracement = 0.4
    private var maxDailyLossPercent = 10.0
    private var pauseOnLossEnabled = true

    // Circuit Breaker & Rachas
    private var initialBalance = 0.0
    private var stopLossTriggered = false
    private var consecutiveLosses = 0
    private var pauseUntil = 0L

    // API Keys
    private var apiKey = ""
    private var apiSecret = ""

    // Datos Mercado
    private val historialPreciosMap = mutableMapOf<String, MutableList<Double>>()
    private val historialVolumenMap = mutableMapOf<String, MutableList<Double>>()

    // Estado de Posición
    private var posicionAbierta = false
    private var monedaConPosicion = ""
    private var precioEntrada = 0.0
    private var tipoPosicion = ""
    private var maxPnLAlcanzado = 0.0
    private var ultimaOperacionTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanalesNotificacion()

        // 🆕 FASE 2: Escuchar Botón de Pánico desde la UI
        manualCloseReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "FORZAR_CIERRE_MANUAL" && posicionAbierta) {
                    agregarLog("⚠️ ORDEN MANUAL RECIBIDA: Cerrando posición en pánico...")
                    CoroutineScope(Dispatchers.IO).launch {
                        val precioActual = obtenerPrecioReal(monedaConPosicion)
                        if (precioActual > 0) {
                            cerrarTrade(precioActual, "⚠️ Cierre Manual (Pánico)")
                        } else {
                            val precioBackup = lastPrice.replace(",", ".").toDoubleOrNull() ?: precioEntrada
                            cerrarTrade(precioBackup, "⚠️ Cierre Manual (Estimado)")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter("FORZAR_CIERRE_MANUAL")
        ContextCompat.registerReceiver(this, manualCloseReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            sincronizarAjustes()
            restaurarEstado()
            isRunning = true

            if (initialBalance == 0.0) initialBalance = currentBalance

            val notifText = "Estrategia: $activeStrategy | TF: $timeframe"
            val notificacion = createForegroundNotification("SignalFusion Pro", notifText)

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notificacion, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, notificacion)
            }

            iniciarCicloTrading()
        }
        return START_STICKY
    }

    private fun guardarEstado() {
        val prefs = getSharedPreferences("BotState", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putBoolean("POSICION_ABIERTA", posicionAbierta)
        editor.putString("MONEDA_POSICION", monedaConPosicion)
        editor.putString("TIPO_POSICION", tipoPosicion)
        editor.putFloat("PRECIO_ENTRADA", precioEntrada.toFloat())
        editor.putFloat("MAX_PNL", maxPnLAlcanzado.toFloat())
        editor.putLong("ULTIMA_OP_TIME", ultimaOperacionTime)
        editor.putFloat("CURRENT_BALANCE", currentBalance.toFloat())
        editor.putFloat("INITIAL_BALANCE", initialBalance.toFloat())
        editor.putInt("TOTAL_TRADES", totalTrades)
        editor.putInt("WINS", tradesGanados)
        editor.putInt("LOSSES", tradesPerdidos)
        editor.putBoolean("STOP_LOSS_TRIGGERED", stopLossTriggered)
        editor.putInt("CONS_LOSSES", consecutiveLosses)
        editor.putLong("PAUSE_UNTIL", pauseUntil)
        editor.apply()
    }

    private fun restaurarEstado() {
        val prefs = getSharedPreferences("BotState", Context.MODE_PRIVATE)
        posicionAbierta = prefs.getBoolean("POSICION_ABIERTA", false)
        monedaConPosicion = prefs.getString("MONEDA_POSICION", "") ?: ""
        tipoPosicion = prefs.getString("TIPO_POSICION", "") ?: ""
        precioEntrada = prefs.getFloat("PRECIO_ENTRADA", 0f).toDouble()
        maxPnLAlcanzado = prefs.getFloat("MAX_PNL", 0f).toDouble()
        ultimaOperacionTime = prefs.getLong("ULTIMA_OP_TIME", 0L)
        val savedBalance = prefs.getFloat("CURRENT_BALANCE", -1f).toDouble(); if (savedBalance > 0) currentBalance = savedBalance
        val savedInitial = prefs.getFloat("INITIAL_BALANCE", -1f).toDouble(); if (savedInitial > 0) initialBalance = savedInitial
        totalTrades = prefs.getInt("TOTAL_TRADES", 0); tradesGanados = prefs.getInt("WINS", 0)
        tradesPerdidos = prefs.getInt("LOSSES", 0); stopLossTriggered = prefs.getBoolean("STOP_LOSS_TRIGGERED", false)
        consecutiveLosses = prefs.getInt("CONS_LOSSES", 0); pauseUntil = prefs.getLong("PAUSE_UNTIL", 0L)

        if (posicionAbierta) {
            agregarLog("💾 MEMORIA RESTAURADA: Operación $tipoPosicion en $monedaConPosicion sigue activa.")
            symbolBeingScanned = monedaConPosicion
        } else {
            agregarLog("💾 MEMORIA RESTAURADA: Sin operaciones abiertas. Balance: $%.2f".format(currentBalance))
        }
    }

    private fun sincronizarAjustes() {
        val prefs = getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        val configBalance = prefs.getString("AMOUNT", "1000")?.toDoubleOrNull() ?: 1000.0
        if (currentBalance <= 1000.0) currentBalance = configBalance

        apiKey = prefs.getString("API_KEY", "") ?: ""
        apiSecret = prefs.getString("SECRET_KEY", "") ?: ""
        targetTP = prefs.getString("TP_VAL", "2.0")?.toDoubleOrNull() ?: 2.0
        targetSL = prefs.getString("SL_VAL", "1.5")?.toDoubleOrNull() ?: 1.5
        leverage = prefs.getInt("LEVERAGE", 5).toDouble()
        isTurbo = prefs.getBoolean("TURBO_MODE", false)

        // 🆕 AHORA SÍ: LEEMOS EL RIESGO QUE PONES EN AJUSTES
        val riesgoStr = prefs.getString("RISK_PERCENT", "5.0")?.replace(",", ".") ?: "5.0"
        val riesgoPorcentaje = riesgoStr.toDoubleOrNull() ?: 5.0

        // Calculamos el tamaño: Balance * (Porcentaje / 100)
        tamanoPosicionUSDT = currentBalance * (riesgoPorcentaje / 100.0)

        // Seguridad: Nunca usar más del 50% en una sola operación
        if (tamanoPosicionUSDT > currentBalance * 0.5) {
            tamanoPosicionUSDT = currentBalance * 0.5
        }

        activeStrategy = prefs.getString("STRATEGY", "MODERADA") ?: "MODERADA"

        timeframe = prefs.getString("TIMEFRAME", "1m") ?: "1m"
        tsActivation = prefs.getFloat("TS_ACTIV", 1.3f).toDouble()
        maxDailyLossPercent = prefs.getFloat("MAX_DAILY_LOSS", 10.0f).toDouble()
        pauseOnLossEnabled = prefs.getBoolean("PAUSE_ON_LOSS", true)

        activeSymbols.clear()
        if (prefs.getBoolean("COIN_BTC", true)) activeSymbols.add("BTCUSDT")
        if (prefs.getBoolean("COIN_ETH", false)) activeSymbols.add("ETHUSDT")
        if (prefs.getBoolean("COIN_SOL", false)) activeSymbols.add("SOLUSDT")
        if (prefs.getBoolean("COIN_XRP", false)) activeSymbols.add("XRPUSDT")
        if (activeSymbols.isEmpty()) activeSymbols.add("BTCUSDT")

        activeSymbols.forEach { symbol ->
            if (!historialPreciosMap.containsKey(symbol)) {
                historialPreciosMap[symbol] = mutableListOf()
                historialVolumenMap[symbol] = mutableListOf()
            }
        }
    }

    private fun iniciarCicloTrading() {
        job = CoroutineScope(Dispatchers.IO).launch {
            agregarLog("🚀 SignalFusion Pro v3.4 Iniciado | TF: $timeframe")

            activeSymbols.forEach { symbol ->
                descargarHistorialInicial(symbol)
                delay(1000)
            }

            while (isActive) {
                try {
                    if (verificarSaludFinanciera()) {
                        stopSelf(); break
                    }

                    if (pauseOnLossEnabled && System.currentTimeMillis() < pauseUntil) {
                        actualizarEstadoUI("⏳ Pausado por protección (Racha perdedora)")
                        delay(10000); continue
                    }

                    if (posicionAbierta && monedaConPosicion.isNotEmpty()) {
                        symbolBeingScanned = monedaConPosicion
                    } else {
                        symbolBeingScanned = activeSymbols[currentSymbolIndex]
                    }

                    val precioActual = obtenerPrecioReal(symbolBeingScanned)
                    val volumenActual = obtenerVolumenReal(symbolBeingScanned)

                    if (precioActual > 0) {
                        actualizarDatos(symbolBeingScanned, precioActual, volumenActual)
                        val historialP = historialPreciosMap[symbolBeingScanned] ?: mutableListOf()
                        val historialV = historialVolumenMap[symbolBeingScanned] ?: mutableListOf()

                        if (historialP.size >= 50) {
                            when (activeStrategy) {
                                "AGRESIVA" -> ejecutarEstrategiaAgresiva(symbolBeingScanned, precioActual, volumenActual, historialP, historialV)
                                "BREAKOUT" -> ejecutarEstrategiaBreakout(symbolBeingScanned, precioActual, volumenActual, historialP, historialV)
                                else -> ejecutarEstrategiaModerada(symbolBeingScanned, precioActual, volumenActual, historialP, historialV)
                            }
                        } else {
                            actualizarEstadoUI("📥 Cargando $symbolBeingScanned: ${historialP.size}/50")
                        }
                    } else {
                        actualizarEstadoUI("⚠️ Error precio $symbolBeingScanned")
                    }

                    if (!posicionAbierta) {
                        currentSymbolIndex++
                        if (currentSymbolIndex >= activeSymbols.size) currentSymbolIndex = 0
                    }

                } catch (e: Exception) {
                    agregarLog("❌ Error: ${e.message}")
                }
                delay(if (posicionAbierta) 2000L else if (activeStrategy == "AGRESIVA" && isTurbo) 2000L else 4000L)
            }
        }
    }

    private fun verificarSaludFinanciera(): Boolean {
        if (initialBalance == 0.0) return false
        val perdidaTotal = initialBalance - currentBalance
        val porcentajePerdida = (perdidaTotal / initialBalance) * 100

        if (porcentajePerdida >= maxDailyLossPercent) {
            val mensaje = "⛔ CIRCUIT BREAKER: Pérdida > ${maxDailyLossPercent}%. Bot detenido."
            if (!stopLossTriggered) {
                agregarLog(mensaje)
                enviarAlertaPush("SISTEMA DETENIDO", "Límite de pérdidas alcanzado.", true)
                actualizarEstadoUI("⛔ DETENIDO POR PROTECCIÓN")
                stopLossTriggered = true
                guardarEstado()
            }
            isRunning = false
            return true
        }
        return false
    }

    // --- ESTRATEGIAS ---
    private fun ejecutarEstrategiaModerada(symbol: String, precio: Double, volumen: Double, historyP: List<Double>, historyV: List<Double>) {
        val rsi = Indicadores.calcularRSI(historyP, 14)
        val ema9 = Indicadores.calcularEMA(historyP, 9)
        val ema21 = Indicadores.calcularEMA(historyP, 21)
        val atr = Indicadores.calcularATR(historyP, 14)
        val volPromedio = historyV.takeLast(20).average()
        val volatilidad = (atr / precio) * 100
        actualizarMemoriaCache(symbol, rsi, volatilidad, ema9 > ema21, precio)

        if (!posicionAbierta) {
            val cooldownOk = System.currentTimeMillis() - ultimaOperacionTime > 60000
            val volFuerte = volumen > volPromedio * 1.1
            val volatilNoExtrema = volatilidad < 3.5
            if (!cooldownOk) { actualizarEstadoUI("⏳ Cooldown..."); return }
            val rsiBajo = if (isTurbo) rsi < 40 else rsi < 35
            val rsiAlto = if (isTurbo) rsi > 60 else rsi > 65
            if (volFuerte && rsiBajo && (ema9 > ema21) && volatilNoExtrema) abrirTrade(symbol, precio, "LONG", volatilidad)
            else if (volFuerte && rsiAlto && (ema9 < ema21) && volatilNoExtrema) abrirTrade(symbol, precio, "SHORT", volatilidad)
            else actualizarEstadoUI("🔍 $symbol | RSI:%.0f | Vol:%.1f%%".format(rsi, volatilidad))
        } else {
            gestionarSalida(symbol, precio, rsi, ema9, ema21, volatilidad, 30.0, 70.0)
        }
    }

    private fun ejecutarEstrategiaAgresiva(symbol: String, precio: Double, volumen: Double, historyP: List<Double>, historyV: List<Double>) {
        val rsi = Indicadores.calcularRSI(historyP, 14)
        val ema9 = Indicadores.calcularEMA(historyP, 9)
        val ema21 = Indicadores.calcularEMA(historyP, 21)
        val atr = Indicadores.calcularATR(historyP, 14)
        val volatilidad = (atr / precio) * 100
        actualizarMemoriaCache(symbol, rsi, volatilidad, ema9 > ema21, precio)

        if (!posicionAbierta) {
            val cooldownOk = System.currentTimeMillis() - ultimaOperacionTime > 30000
            if (!cooldownOk) return
            val rsiBajo = rsi < 40; val rsiAlto = rsi > 60
            if (rsiBajo && volatilidad < 5.0) abrirTrade(symbol, precio, "LONG", volatilidad)
            else if (rsiAlto && volatilidad < 5.0) abrirTrade(symbol, precio, "SHORT", volatilidad)
            else actualizarEstadoUI("🔥 $symbol | RSI:%.0f | Vol:%.1f%%".format(rsi, volatilidad))
        } else {
            gestionarSalida(symbol, precio, rsi, ema9, ema21, volatilidad, 35.0, 65.0)
        }
    }

    private fun ejecutarEstrategiaBreakout(symbol: String, precio: Double, volumen: Double, historyP: List<Double>, historyV: List<Double>) {
        val ema9 = Indicadores.calcularEMA(historyP, 9)
        val rsi = Indicadores.calcularRSI(historyP, 14)
        actualizarMemoriaCache(symbol, rsi, 0.0, true, precio)
        if (!posicionAbierta) {
            val breakoutUp = precio > ema9 * 1.005 && rsi > 55
            val breakoutDown = precio < ema9 * 0.995 && rsi < 45
            if (breakoutUp) abrirTrade(symbol, precio, "LONG", 0.0)
            else if (breakoutDown) abrirTrade(symbol, precio, "SHORT", 0.0)
            else actualizarEstadoUI("🎯 $symbol | Buscando Ruptura...")
        } else {
            gestionarSalida(symbol, precio, rsi, ema9, ema9, 0.0, 30.0, 70.0)
        }
    }

    private fun abrirTrade(symbol: String, precio: Double, tipo: String, volatilidad: Double) {
        posicionAbierta = true
        monedaConPosicion = symbol
        tipoPosicion = tipo
        precioEntrada = precio
        maxPnLAlcanzado = 0.0
        ultimaOperacionTime = System.currentTimeMillis()
        totalTrades++
        val msg = "OPEN $tipo en $symbol @ %.2f".format(precio)
        agregarLog(msg)
        enviarAlertaPush("OPERACIÓN ABIERTA 🚀", "$tipo en $symbol @ $precio", false)
        guardarEstado()
    }

    private fun cerrarTrade(precio: Double, motivo: String) {
        val pnl = calcularPnL(precio)
        val resultadoUSDT = tamanoPosicionUSDT * (pnl / 100)
        currentBalance += resultadoUSDT

        // 🆕 FASE 3: GRÁFICO - ¡AQUÍ ES DONDE FALTABA LA LLAMADA!
        registrarPuntoGrafico(currentBalance)

        if (resultadoUSDT > 0) {
            tradesGanados++
            consecutiveLosses = 0
        } else {
            tradesPerdidos++
            consecutiveLosses++
            if (consecutiveLosses >= 5 && pauseOnLossEnabled) {
                pauseUntil = System.currentTimeMillis() + 3600_000L
                agregarLog("⚠️ 5 PÉRDIDAS SEGUIDAS. Bot pausado por 1 hora.")
                enviarAlertaPush("SISTEMA PAUSADO 🛡️", "Racha perdedora. Pausa de 1 hora.", true)
                consecutiveLosses = 0
            }
        }
        val msg = "CLOSE | $motivo | PnL: %.2f%% ($%.2f)".format(pnl, resultadoUSDT)
        agregarLog(msg)
        enviarAlertaPush("OPERACIÓN CERRADA 💰", "$motivo: $%.2f USDT".format(resultadoUSDT), resultadoUSDT > 0)
        posicionAbierta = false; monedaConPosicion = ""; tipoPosicion = ""
        guardarEstado()
    }

    private fun gestionarSalida(symbol: String, precio: Double, rsi: Double, ema9: Double, ema21: Double, volatilidad: Double, rsiMin: Double, rsiMax: Double) {
        val pnl = calcularPnL(precio)
        if (pnl > maxPnLAlcanzado) { maxPnLAlcanzado = pnl; guardarEstado() }
        val emoji = if (pnl > 0) "📈" else "🔴"
        actualizarEstadoUI("$emoji $symbol $tipoPosicion | PnL:%.2f%%".format(pnl))
        if (pnl >= targetTP) cerrarTrade(precio, "✅ Take Profit")
        else if (pnl <= -targetSL) cerrarTrade(precio, "🛑 Stop Loss")
        if (maxPnLAlcanzado >= tsActivation && (maxPnLAlcanzado - pnl) >= tsRetracement) {
            cerrarTrade(precio, "🛡️ Trailing Stop")
        }
    }

    private fun calcularPnL(actual: Double): Double {
        return when (tipoPosicion) {
            "LONG" -> ((actual - precioEntrada) / precioEntrada) * 100 * leverage
            "SHORT" -> ((precioEntrada - actual) / precioEntrada) * 100 * leverage
            else -> 0.0
        }
    }

    private fun crearCanalesNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID_FOREGROUND, "Motor SignalFusion", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID_ALERTS, "Alertas de Trading", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        }
    }

    private fun createForegroundNotification(titulo: String, contenido: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND).setContentTitle(titulo).setContentText(contenido).setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true).build()
    }

    private fun enviarAlertaPush(titulo: String, mensaje: String, esCritica: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID_ALERTS).setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle(titulo).setContentText(mensaje).setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pendingIntent).setAutoCancel(true)
        if (esCritica) builder.setDefaults(Notification.DEFAULT_ALL)
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun actualizarMemoriaCache(symbol: String, rsi: Double, vol: Double, trendUp: Boolean, precio: Double) {
        lastRSI = "%.0f".format(rsi); lastVol = "%.1f%%".format(vol); lastTrend = if (trendUp) "LONG ↗" else "SHORT ↘"; lastPrice = "%.2f".format(precio); symbolBeingScanned = symbol
    }

    private suspend fun obtenerPrecioReal(symbol: String): Double = withContext(Dispatchers.IO) {
        val url = "https://api.bitget.com/api/v2/mix/market/ticker?symbol=$symbol&productType=USDT-FUTURES"
        try { client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) JSONObject(response.body?.string() ?: "{}").getJSONArray("data").getJSONObject(0).getString("lastPr").toDouble() else 0.0
        }} catch (e: Exception) { 0.0 }
    }

    private suspend fun obtenerVolumenReal(symbol: String): Double = withContext(Dispatchers.IO) {
        val url = "https://api.bitget.com/api/v2/mix/market/ticker?symbol=$symbol&productType=USDT-FUTURES"
        try { client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) JSONObject(response.body?.string() ?: "{}").getJSONArray("data").getJSONObject(0).getString("baseVolume").toDoubleOrNull() ?: 0.0 else 0.0
        }} catch (e: Exception) { 0.0 }
    }

    private suspend fun descargarHistorialInicial(symbol: String) = withContext(Dispatchers.IO) {
        val url = "https://api.bitget.com/api/v2/mix/market/candles?symbol=$symbol&productType=USDT-FUTURES&granularity=$timeframe&limit=100"
        try { client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) {
                val data = JSONObject(response.body?.string() ?: "{}").getJSONArray("data")
                val precios = mutableListOf<Double>(); val volumenes = mutableListOf<Double>()
                for (i in 0 until data.length()) {
                    val c = data.getJSONArray(i)
                    precios.add(c.getString(4).toDouble()); volumenes.add(c.getString(5).toDouble())
                }
                historialPreciosMap[symbol] = precios; historialVolumenMap[symbol] = volumenes
                agregarLog("✅ Historial ($timeframe) cargado: $symbol")
            }
        }} catch (e: Exception) { agregarLog("❌ Error carga $symbol: ${e.message}") }
    }

    private fun actualizarDatos(symbol: String, precio: Double, volumen: Double) {
        val listaP = historialPreciosMap[symbol] ?: return; val listaV = historialVolumenMap[symbol] ?: return
        listaP.add(precio); listaV.add(volumen); if (listaP.size > 100) { listaP.removeAt(0); listaV.removeAt(0) }
    }

    private fun agregarLog(mensaje: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val linea = "[$timestamp] $mensaje\n"
        logHistory.insert(0, linea)
        sendBroadcast(Intent("ACTUALIZACION_TRADING").putExtra("LOG_MSG", linea))
    }

    // 🆕 ACTUALIZACIÓN FASE 2: Enviar datos detallados a la Tarjeta UI
    private fun actualizarEstadoUI(mensaje: String) {
        currentStatus = mensaje
        val intent = Intent("ACTUALIZACION_TRADING")
        intent.putExtra("STATUS_MSG", mensaje)
        intent.putExtra("TOTAL_TRADES", totalTrades)
        intent.putExtra("WINS", tradesGanados)
        intent.putExtra("BALANCE", currentBalance)
        intent.putExtra("INITIAL_BALANCE", initialBalance)

        // DATOS PARA LA TARJETA EN VIVO
        intent.putExtra("IS_TRADE_OPEN", posicionAbierta)
        if (posicionAbierta) {
            intent.putExtra("TRADE_SYMBOL", monedaConPosicion)
            intent.putExtra("TRADE_TYPE", tipoPosicion)
            intent.putExtra("TRADE_START_TIME", ultimaOperacionTime)

            // Calculamos un PnL estimado para que la tarjeta se mueva
            val precioEstimado = lastPrice.replace(",", ".").toDoubleOrNull() ?: precioEntrada
            val pnlEstimado = if (precioEstimado > 0) calcularPnL(precioEstimado) else 0.0
            intent.putExtra("TRADE_PNL", pnlEstimado)
        }

        sendBroadcast(intent)
    }
    // 🆕 FASE 3: Guardar puntos para el gráfico
    private fun registrarPuntoGrafico(nuevoBalance: Double) {
        val prefs = getSharedPreferences("BotHistory", Context.MODE_PRIVATE)
        val historialActual = prefs.getString("GRAPH_DATA", "") ?: ""

        // Formato: "TIEMPO:DINERO;" (Ej: "1782382:1005.20;")
        val nuevoPunto = "${System.currentTimeMillis()}:$nuevoBalance;"

        // Guardamos (concatenando al final)
        prefs.edit().putString("GRAPH_DATA", historialActual + nuevoPunto).apply()
    }

    override fun onDestroy() {
        isRunning = false; job?.cancel()
        // 🆕 Limpiar receptor
        try { manualCloseReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        super.onDestroy()
    }
}