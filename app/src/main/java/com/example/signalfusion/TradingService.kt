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
        var currentBalance = 0.0 // 🟢 MODO REAL: Se lee desde Bitget

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

    // Receptor del Botón de Pánico
    private var manualCloseReceiver: BroadcastReceiver? = null

    // Configuración Base
    private var targetTP = 2.0
    private var targetSL = 1.5
    private var leverage = 5.0
    private var isTurbo = false
    private var activeStrategy = "MODERADA"
    private var riskPercent = 20.0

    // 🔐 CREDENCIALES BITGET (REAL)
    private var apiKey = ""
    private var apiSecret = ""
    private var apiPassphrase = "" // ⚠️ IMPORTANTE: Passphrase de la API

    // Multi-Moneda
    private var activeSymbols = mutableListOf<String>()
    private var currentSymbolIndex = 0
    private var symbolBeingScanned = "BTCUSDT"

    // Parámetros
    private var timeframe = "1m"
    private var tsActivation = 1.2
    private var tsRetracement = 0.4
    private var pauseOnLossEnabled = true

    // Circuit Breaker & Rachas
    private var initialBalance = 0.0
    private var stopLossTriggered = false
    private var consecutiveLosses = 0
    private var pauseUntil = 0L

    // Estado de Posición (REAL)
    private var posicionAbierta = false
    private var monedaConPosicion = ""
    private var tipoPosicion = ""
    private var precioEntrada = 0.0
    private var maxPnLAlcanzado = 0.0
    private var ultimaOperacionTime = 0L

    // Historial Datos
    private val historialPreciosMap = mutableMapOf<String, MutableList<Double>>()
    private val historialVolumenMap = mutableMapOf<String, MutableList<Double>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanalesNotificacion()

        // Escuchar Botón de Pánico desde la UI (REAL)
        manualCloseReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "FORZAR_CIERRE_MANUAL" && posicionAbierta) {
                    agregarLog("⚠️ PÁNICO: Enviando orden de cierre a Bitget...")
                    CoroutineScope(Dispatchers.IO).launch {
                        cerrarTradeReal("Manual Panic Button")
                    }
                }
            }
        }
        val filter = IntentFilter("FORZAR_CIERRE_MANUAL")
        ContextCompat.registerReceiver(this, manualCloseReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sincronizarAjustes()

        // 🔄 INICIO: CONECTAR CON LA WALLET REAL
        agregarLog("📡 Conectando a Bitget Futures...")

        CoroutineScope(Dispatchers.IO).launch {
            val saldoReal = obtenerBalanceBitget()

            if (saldoReal > 0) {
                currentBalance = saldoReal
                // Si es un reinicio manual, reseteamos la base
                initialBalance = currentBalance
                stopLossTriggered = false
                consecutiveLosses = 0
                pauseUntil = 0L

                agregarLog("✅ CONEXIÓN ÉXITOSA: Saldo disponible $${"%.2f".format(currentBalance)}")
                isRunning = true
                guardarEstado()
                iniciarCicloTrading()
            } else {
                agregarLog("❌ ERROR CRÍTICO: No se pudo leer el saldo de Bitget. Revisa API Keys y Passphrase.")
                stopSelf() // Nos apagamos si no hay conexión al dinero
            }
        }

        val notif = createForegroundNotification("SignalFusion REAL", "Motor Activo - Bitget API")

        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } catch (e: Exception) {
                startForeground(1, notif)
            }
        } else {
            startForeground(1, notif)
        }

        return START_STICKY
    }

    // ----------------------------------------------------------------
    // 📡 FUNCIONES DE CONEXIÓN REAL (BITGET API V2)
    // ----------------------------------------------------------------

    private suspend fun obtenerBalanceBitget(): Double = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty() || apiSecret.isEmpty() || apiPassphrase.isEmpty()) {
            agregarLog("⚠️ Faltan credenciales API en Ajustes")
            return@withContext 0.0
        }

        val path = "/api/v2/mix/account/accounts?productType=USDT-FUTURES"
        val jsonStr = BitgetUtils.authenticatedGet(path, apiKey, apiSecret, apiPassphrase)

        try {
            if (jsonStr != null) {
                val json = JSONObject(jsonStr)
                if (json.getString("code") == "00000") {
                    val data = json.getJSONArray("data")
                    for (i in 0 until data.length()) {
                        val asset = data.getJSONObject(i)
                        if (asset.getString("marginCoin") == "USDT") {
                            return@withContext asset.getString("available").toDouble()
                        }
                    }
                } else {
                    agregarLog("Error API Balance: ${json.getString("msg")}")
                }
            }
        } catch (e: Exception) { agregarLog("Excepción Balance: ${e.message}") }
        return@withContext 0.0
    }

    private fun abrirTradeReal(symbol: String, precio: Double, tipo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            // 1. Calcular Tamaño de Posición
            // Bitget pide "size" en la cantidad de la moneda (BTC, ETH), NO en USDT.
            val margenUsdt = currentBalance * (riskPercent / 100.0) // Ej: $20 * 20% = $4
            val poderCompra = margenUsdt * leverage // $4 * 5x = $20

            // Tamaño en Moneda = Total USDT / Precio Actual
            val sizeAmount = poderCompra / precio
            // Formateamos a 3 decimales (Bitget es estricto)
            val sizeStr = String.format(Locale.US, "%.3f", sizeAmount)

            val side = if (tipo == "LONG") "buy" else "sell"

            agregarLog("📤 ENVIANDO ORDEN: $side $sizeStr $symbol (Margen: $${"%.2f".format(margenUsdt)})")

            // 2. Construir JSON
            val jsonBody = JSONObject()
            jsonBody.put("symbol", symbol)
            jsonBody.put("productType", "USDT-FUTURES")
            jsonBody.put("marginMode", "crossed") // Modo Cruzado
            jsonBody.put("marginCoin", "USDT")
            jsonBody.put("size", sizeStr)
            jsonBody.put("side", side)
            jsonBody.put("orderType", "market") // Entramos a Mercado (agresivo)
            jsonBody.put("force", "normal")

            // 3. Enviar a Bitget
            val response = BitgetUtils.authenticatedPost("/api/v2/mix/order/place-order", jsonBody.toString(), apiKey, apiSecret, apiPassphrase)

            if (response != null && response.contains("\"code\":\"00000\"")) {
                // Éxito Real
                posicionAbierta = true
                monedaConPosicion = symbol
                tipoPosicion = tipo
                precioEntrada = precio
                maxPnLAlcanzado = 0.0
                ultimaOperacionTime = System.currentTimeMillis()

                enviarAlertaPush("REAL TRADE 🚀", "$tipo $symbol Ejecutado", false)
                agregarLog("✅ ORDEN CONFIRMADA POR EXCHANGE")
                guardarEstado()
            } else {
                agregarLog("❌ ERROR AL ABRIR: $response")
            }
        }
    }

    private fun cerrarTradeReal(motivo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            agregarLog("📤 CERRANDO POSICIÓN ($motivo)...")

            // Usamos "Close Positions" para cerrar todo en ese símbolo (Flash Close)
            val jsonBody = JSONObject()
            jsonBody.put("symbol", monedaConPosicion)
            jsonBody.put("productType", "USDT-FUTURES")
            // Si estoy en LONG, cierro mi posición 'long', si estoy en SHORT, cierro 'short'
            val holdSide = if (tipoPosicion == "LONG") "long" else "short"
            jsonBody.put("holdSide", holdSide)

            val response = BitgetUtils.authenticatedPost("/api/v2/mix/order/close-positions", jsonBody.toString(), apiKey, apiSecret, apiPassphrase)

            if (response != null && response.contains("\"code\":\"00000\"")) {
                // Verificar resultado financiero
                val nuevoSaldo = obtenerBalanceBitget()
                val pnlReal = if (nuevoSaldo > 0) nuevoSaldo - currentBalance else 0.0

                if (nuevoSaldo > 0) currentBalance = nuevoSaldo

                if (pnlReal > 0) {
                    tradesGanados++
                    consecutiveLosses = 0
                } else {
                    tradesPerdidos++
                    consecutiveLosses++
                    if (consecutiveLosses >= 5 && pauseOnLossEnabled) {
                        pauseUntil = System.currentTimeMillis() + 3600_000L
                        enviarAlertaPush("SISTEMA PAUSADO 🛡️", "Racha real detectada. Pausa 1h.", true)
                    }
                }

                agregarLog("🏁 CIERRE CONFIRMADO: $motivo | PnL Real: $${"%.2f".format(pnlReal)}")
                enviarAlertaPush("CIERRE REAL 💰", "Saldo: $${"%.2f".format(currentBalance)}", pnlReal > 0)

                posicionAbierta = false
                monedaConPosicion = ""
                guardarEstado()
                registrarPuntoGrafico(currentBalance)
            } else {
                agregarLog("❌ ERROR AL CERRAR: $response")
                // Si falla el cierre por API, reintentamos en el siguiente ciclo
            }
        }
    }

    // ----------------------------------------------------------------
    // 🧠 LÓGICA DE TRADING (REAL)
    // ----------------------------------------------------------------

    private fun iniciarCicloTrading() {
        job = CoroutineScope(Dispatchers.IO).launch {
            activeSymbols.forEach { descargarHistorialInicial(it); delay(1000) }

            while (isActive) {
                try {
                    // Check Circuit Breaker
                    if (initialBalance > 0 && currentBalance < initialBalance * 0.9) {
                        agregarLog("⛔ PÉRDIDA MÁXIMA ALCANZADA (-10%). APAGANDO.")
                        stopSelf(); break
                    }

                    if (pauseOnLossEnabled && System.currentTimeMillis() < pauseUntil) {
                        actualizarEstadoUI("⏳ Pausado por racha perdedora..."); delay(10000); continue
                    }

                    val symbol = if (posicionAbierta) monedaConPosicion else activeSymbols[currentSymbolIndex]
                    val precio = obtenerPrecioReal(symbol) // Precio público (sin auth)
                    val volumen = obtenerVolumenReal(symbol)

                    if (precio > 0) {
                        actualizarDatos(symbol, precio, volumen)
                        val hP = historialPreciosMap[symbol] ?: mutableListOf()
                        val hV = historialVolumenMap[symbol] ?: mutableListOf()

                        if (hP.size >= 50) {
                            if (!posicionAbierta) {
                                ejecutarEstrategiaModerada(symbol, precio, volumen, hP, hV)
                            } else {
                                gestionarSalida(symbol, precio)
                            }
                        } else {
                            actualizarEstadoUI("📥 Cargando datos $symbol...")
                        }
                    }

                    if (!posicionAbierta) currentSymbolIndex = (currentSymbolIndex + 1) % activeSymbols.size

                } catch (e: Exception) { agregarLog("Error Loop: ${e.message}") }
                delay(if (posicionAbierta) 2000 else 4000)
            }
        }
    }

    private fun ejecutarEstrategiaModerada(symbol: String, precio: Double, volumen: Double, hP: List<Double>, hV: List<Double>) {
        val rsi = Indicadores.calcularRSI(hP, 14)
        val ema9 = Indicadores.calcularEMA(hP, 9)
        val ema21 = Indicadores.calcularEMA(hP, 21)
        val atr = Indicadores.calcularATR(hP, 14)
        val volatilidad = (atr / precio) * 100

        actualizarMemoriaCache(symbol, rsi, volatilidad, ema9 > ema21, precio)

        // Filtros de Seguridad
        if (System.currentTimeMillis() - ultimaOperacionTime < 120000) { actualizarEstadoUI("⏳ Enfriando..."); return }
        val volProm = hV.takeLast(20).average()
        if (volumen < volProm * 1.2) return // Filtro de Volumen
        if (volatilidad < 0.1 || volatilidad > 3.0) return // Filtro Volatilidad

        // SEÑAL DE ENTRADA REAL
        if (rsi < 35 && ema9 > ema21) abrirTradeReal(symbol, precio, "LONG")
        else if (rsi > 65 && ema9 < ema21) abrirTradeReal(symbol, precio, "SHORT")
    }

    private fun gestionarSalida(symbol: String, precio: Double) {
        // Calculamos PnL Localmente para decidir cuándo cerrar
        val pnl = calcularPnL(precio)
        if (pnl > maxPnLAlcanzado) maxPnLAlcanzado = pnl

        val emoji = if (pnl > 0) "📈" else "🔻"
        actualizarEstadoUI("$emoji REAL $symbol | PnL: %.2f%%".format(pnl))

        if (pnl >= targetTP) cerrarTradeReal("Take Profit ✅")
        else if (pnl <= -targetSL) cerrarTradeReal("Stop Loss 🛑")
        else if (maxPnLAlcanzado >= tsActivation && (maxPnLAlcanzado - pnl) >= tsRetracement) {
            cerrarTradeReal("Trailing Stop 🛡️")
        }
    }

    // ----------------------------------------------------------------
    // 🛠️ UTILIDADES Y SOPORTE
    // ----------------------------------------------------------------

    private fun sincronizarAjustes() {
        val prefs = getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        apiKey = prefs.getString("API_KEY", "") ?: ""
        apiSecret = prefs.getString("SECRET_KEY", "") ?: ""
        // ⚠️ IMPORTANTE: Asegúrate de guardar esto en SharedPrefs con clave API_PASSPHRASE
        apiPassphrase = prefs.getString("API_PASSPHRASE", "") ?: ""

        targetTP = prefs.getString("TP_VAL", "2.0")?.toDoubleOrNull() ?: 2.0
        targetSL = prefs.getString("SL_VAL", "1.5")?.toDoubleOrNull() ?: 1.5
        leverage = prefs.getInt("LEVERAGE", 5).toDouble()

        val riskStr = prefs.getString("RISK_PERCENT", "20.0")?.replace(",", ".") ?: "20.0"
        riskPercent = riskStr.toDoubleOrNull() ?: 20.0

        activeSymbols.clear()
        if (prefs.getBoolean("COIN_BTC", true)) activeSymbols.add("BTCUSDT")
        if (prefs.getBoolean("COIN_ETH", false)) activeSymbols.add("ETHUSDT")
        if (prefs.getBoolean("COIN_SOL", false)) activeSymbols.add("SOLUSDT")
        if (prefs.getBoolean("COIN_XRP", false)) activeSymbols.add("XRPUSDT")
        if (activeSymbols.isEmpty()) activeSymbols.add("BTCUSDT")

        activeSymbols.forEach {
            if (!historialPreciosMap.containsKey(it)) {
                historialPreciosMap[it] = mutableListOf()
                historialVolumenMap[it] = mutableListOf()
            }
        }
    }

    private fun calcularPnL(actual: Double): Double {
        return when (tipoPosicion) {
            "LONG" -> ((actual - precioEntrada) / precioEntrada) * 100 * leverage
            "SHORT" -> ((precioEntrada - actual) / precioEntrada) * 100 * leverage
            else -> 0.0
        }
    }

    // Funciones Auxiliares UI / Notificaciones
    private fun actualizarEstadoUI(mensaje: String) {
        currentStatus = mensaje
        val intent = Intent("ACTUALIZACION_TRADING")
        intent.putExtra("STATUS_MSG", mensaje)
        intent.putExtra("BALANCE", currentBalance)
        intent.putExtra("INITIAL_BALANCE", initialBalance)
        intent.putExtra("IS_TRADE_OPEN", posicionAbierta)
        if (posicionAbierta) {
            intent.putExtra("TRADE_SYMBOL", monedaConPosicion)
            intent.putExtra("TRADE_TYPE", tipoPosicion)
            val pnlEstimado = calcularPnL(lastPrice.replace(",", ".").toDoubleOrNull() ?: precioEntrada)
            intent.putExtra("TRADE_PNL", pnlEstimado)
        }
        sendBroadcast(intent)
    }

    private fun agregarLog(mensaje: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val linea = "[$timestamp] $mensaje\n"
        logHistory.insert(0, linea)
        sendBroadcast(Intent("ACTUALIZACION_TRADING").putExtra("LOG_MSG", linea))
    }

    private fun actualizarMemoriaCache(symbol: String, rsi: Double, vol: Double, trendUp: Boolean, precio: Double) {
        lastRSI = "%.0f".format(rsi); lastVol = "%.1f%%".format(vol); lastTrend = if (trendUp) "LONG ↗" else "SHORT ↘"; lastPrice = "%.2f".format(precio); symbolBeingScanned = symbol
    }

    private fun registrarPuntoGrafico(nuevoBalance: Double) {
        val prefs = getSharedPreferences("BotHistory", Context.MODE_PRIVATE)
        val historialActual = prefs.getString("GRAPH_DATA", "") ?: ""
        val nuevoPunto = "${System.currentTimeMillis()}:$nuevoBalance;"
        prefs.edit().putString("GRAPH_DATA", historialActual + nuevoPunto).apply()
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
    }

    // --- Networking Público (Datos) ---
    private suspend fun obtenerPrecioReal(symbol: String): Double = withContext(Dispatchers.IO) {
        val url = "https://api.bitget.com/api/v2/mix/market/ticker?symbol=$symbol&productType=USDT-FUTURES"
        try { client.newCall(Request.Builder().url(url).build()).execute().use {
            if (it.isSuccessful) JSONObject(it.body?.string()?:"{}").getJSONArray("data").getJSONObject(0).getString("lastPr").toDouble() else 0.0
        }} catch (e: Exception) { 0.0 }
    }

    private suspend fun obtenerVolumenReal(symbol: String): Double = withContext(Dispatchers.IO) {
        val url = "https://api.bitget.com/api/v2/mix/market/ticker?symbol=$symbol&productType=USDT-FUTURES"
        try { client.newCall(Request.Builder().url(url).build()).execute().use {
            if (it.isSuccessful) JSONObject(it.body?.string()?:"{}").getJSONArray("data").getJSONObject(0).getString("baseVolume").toDoubleOrNull() ?: 0.0 else 0.0
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
                agregarLog("✅ Historial cargado: $symbol")
            }
        }} catch (e: Exception) {}
    }

    // --- Notificaciones ---
    private fun crearCanalesNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID_FOREGROUND, "Motor SignalFusion", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID_ALERTS, "Alertas Trading", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        }
    }
    private fun createForegroundNotification(t: String, c: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND).setContentTitle(t).setContentText(c).setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true).build()
    }
    private fun enviarAlertaPush(t: String, m: String, c: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID_ALERTS).setContentTitle(t).setContentText(m).setSmallIcon(android.R.drawable.stat_sys_warning)
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    override fun onDestroy() {
        isRunning = false; job?.cancel()
        try { manualCloseReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        super.onDestroy()
    }
}