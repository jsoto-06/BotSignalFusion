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
        var currentBalance = 0.0

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

    private val CHANNEL_ID_FOREGROUND = "SignalFusionChannel"
    private val CHANNEL_ID_ALERTS = "SignalFusionAlerts"

    private lateinit var manualCloseReceiver: BroadcastReceiver
    private var isReceiverRegistered = false

    // Configuración Base
    private var targetTP = 2.0
    private var targetSL = 1.5
    private var leverage = 5.0
    private var isTurbo = false
    private var activeStrategy = "MODERADA"
    private var riskPercent = 20.0

    // Credenciales
    private var apiKey = ""
    private var apiSecret = ""
    private var apiPassphrase = ""

    // Multi-Moneda
    private var activeSymbols = mutableListOf<String>()
    private var currentSymbolIndex = 0
    private var symbolBeingScanned = "BTCUSDT"

    // Parámetros
    private var timeframe = "1m"
    private var tsActivation = 1.2
    private var tsRetracement = 0.4
    private var pauseOnLossEnabled = true

    // Circuit Breaker
    private var initialBalance = 0.0
    private var stopLossTriggered = false
    private var consecutiveLosses = 0
    private var pauseUntil = 0L

    // Estado de Posición
    private var posicionAbierta = false
    private var monedaConPosicion = ""
    private var tipoPosicion = ""
    private var precioEntrada = 0.0
    private var maxPnLAlcanzado = 0.0
    private var ultimaOperacionTime = 0L

    // Datos
    private val historialPreciosMap = mutableMapOf<String, MutableList<Double>>()
    private val historialVolumenMap = mutableMapOf<String, MutableList<Double>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanalesNotificacion()

        manualCloseReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "FORZAR_CIERRE_MANUAL" && posicionAbierta) {
                    agregarLog("⚠️ PÁNICO: Solicitando cierre inmediato...")
                    CoroutineScope(Dispatchers.IO).launch {
                        cerrarTradeReal("Manual Panic Button")
                    }
                }
            }
        }

        val filter = IntentFilter("FORZAR_CIERRE_MANUAL")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(manualCloseReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(manualCloseReceiver, filter)
        }
        isReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sincronizarAjustes()
        agregarLog("📡 Iniciando conexión con Bitget...")

        CoroutineScope(Dispatchers.IO).launch {
            val saldoReal = obtenerBalanceBitget()

            if (saldoReal > 0) {
                currentBalance = saldoReal
                if (initialBalance == 0.0) initialBalance = currentBalance

                agregarLog("✅ CONEXIÓN ÉXITOSA: Saldo disponible $${"%.2f".format(currentBalance)}")
                isRunning = true
                iniciarCicloTrading()
            } else {
                agregarLog("❌ ERROR DE CONEXIÓN: Revisa tus API Keys y el Saldo en Futuros.")
                stopSelf()
            }
        }

        val notif = createForegroundNotification("SignalFusion REAL", "Motor Activo - $activeStrategy")
        if (Build.VERSION.SDK_INT >= 34) {
            try { startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
            catch (e: Exception) { startForeground(1, notif) }
        } else { startForeground(1, notif) }

        return START_STICKY
    }

    private fun iniciarCicloTrading() {
        job = CoroutineScope(Dispatchers.IO).launch {
            activeSymbols.forEach { descargarHistorialInicial(it); delay(1000) }

            while (isActive) {
                try {
                    if (initialBalance > 0 && currentBalance < initialBalance * 0.9) {
                        agregarLog("⛔ CIRCUIT BREAKER: Pérdida del 10% detectada. Apagando por seguridad.")
                        stopSelf(); break
                    }

                    if (pauseOnLossEnabled && System.currentTimeMillis() < pauseUntil) {
                        actualizarEstadoUI("⏳ Sistema pausado temporalmente..."); delay(10000); continue
                    }

                    val symbol = if (posicionAbierta) monedaConPosicion else activeSymbols[currentSymbolIndex]
                    val precio = obtenerPrecioReal(symbol)
                    val volumen = obtenerVolumenReal(symbol)

                    if (precio > 0) {
                        actualizarDatos(symbol, precio, volumen)
                        val hP = historialPreciosMap[symbol] ?: mutableListOf()
                        val hV = historialVolumenMap[symbol] ?: mutableListOf()

                        if (hP.size >= 50) {
                            if (!posicionAbierta) {
                                when (activeStrategy) {
                                    "AGRESIVA" -> ejecutarEstrategiaAgresiva(symbol, precio, volumen, hP, hV)
                                    "BREAKOUT" -> ejecutarEstrategiaBreakout(symbol, precio, volumen, hP, hV)
                                    else -> ejecutarEstrategiaModerada(symbol, precio, volumen, hP, hV)
                                }
                            } else {
                                gestionarSalida(symbol, precio)
                            }
                        } else {
                            actualizarEstadoUI("📥 Cargando datos $symbol: ${hP.size}/50")
                        }
                    }

                    if (!posicionAbierta) currentSymbolIndex = (currentSymbolIndex + 1) % activeSymbols.size

                } catch (e: Exception) {
                    android.util.Log.e("TradingBot", "Error Loop: ${e.message}")
                }
                delay(if (posicionAbierta) 2000 else 4000)
            }
        }
    }

    // --- ESTRATEGIAS ---
    private fun ejecutarEstrategiaModerada(symbol: String, precio: Double, volumen: Double, hP: List<Double>, hV: List<Double>) {
        val rsi = Indicadores.calcularRSI(hP, 14)
        val ema9 = Indicadores.calcularEMA(hP, 9)
        val ema21 = Indicadores.calcularEMA(hP, 21)

        actualizarMemoriaCache(symbol, rsi, 0.0, ema9 > ema21, precio)
        actualizarEstadoUI("🛡️ MODERADA | $symbol RSI:%.0f".format(rsi))

        if (System.currentTimeMillis() - ultimaOperacionTime < 120000) return
        val volProm = hV.takeLast(20).average()
        if (volumen < volProm * 1.2) return

        if (rsi < 35 && ema9 > ema21) abrirTradeReal(symbol, precio, "LONG")
        else if (rsi > 65 && ema9 < ema21) abrirTradeReal(symbol, precio, "SHORT")
    }

    private fun ejecutarEstrategiaAgresiva(symbol: String, precio: Double, volumen: Double, hP: List<Double>, hV: List<Double>) {
        val rsi = Indicadores.calcularRSI(hP, 14)
        val ema9 = Indicadores.calcularEMA(hP, 9)
        val ema21 = Indicadores.calcularEMA(hP, 21)

        actualizarMemoriaCache(symbol, rsi, 0.0, ema9 > ema21, precio)
        actualizarEstadoUI("⚡ AGRESIVA | $symbol RSI:%.0f".format(rsi))

        if (System.currentTimeMillis() - ultimaOperacionTime < 30000) return

        if (rsi < 40) abrirTradeReal(symbol, precio, "LONG")
        else if (rsi > 60) abrirTradeReal(symbol, precio, "SHORT")
    }

    private fun ejecutarEstrategiaBreakout(symbol: String, precio: Double, volumen: Double, hP: List<Double>, hV: List<Double>) {
        val rsi = Indicadores.calcularRSI(hP, 14)
        val bollingerUp = Indicadores.calcularEMA(hP, 20) + (Indicadores.calcularATR(hP, 20) * 2)
        val bollingerDown = Indicadores.calcularEMA(hP, 20) - (Indicadores.calcularATR(hP, 20) * 2)

        actualizarMemoriaCache(symbol, rsi, 0.0, true, precio)
        actualizarEstadoUI("🚀 BREAKOUT | $symbol | $precio")

        if (System.currentTimeMillis() - ultimaOperacionTime < 60000) return

        if (precio > bollingerUp && rsi > 55) abrirTradeReal(symbol, precio, "LONG")
        else if (precio < bollingerDown && rsi < 45) abrirTradeReal(symbol, precio, "SHORT")
    }

    private fun gestionarSalida(symbol: String, precio: Double) {
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

    // --- CONEXIÓN Y ÓRDENES ---
    private fun abrirTradeReal(symbol: String, precio: Double, tipo: String) {
        CoroutineScope(Dispatchers.IO).launch {

            // Forzar apalancamiento
            val levBody = JSONObject()
            levBody.put("symbol", symbol)
            levBody.put("productType", "usdt-futures")
            levBody.put("marginCoin", "USDT")
            levBody.put("leverage", leverage.toInt().toString())
            levBody.put("holdSide", if (tipo == "LONG") "long" else "short")
            BitgetUtils.authenticatedPost("/api/v2/mix/account/set-leverage", levBody.toString(), apiKey, apiSecret, apiPassphrase)

            val margenUsdt = currentBalance * (riskPercent / 100.0)
            val poderCompra = margenUsdt * leverage
            val rawAmount = poderCompra / precio

            val decimales = when {
                symbol.contains("BTC") -> 3
                symbol.contains("ETH") -> 2
                symbol.contains("SOL") -> 1
                symbol.contains("XRP") -> 0
                else -> 1
            }

            val sizeStr = String.format(Locale.US, "%.${decimales}f", rawAmount)

            if (sizeStr.toDouble() <= 0.0) {
                agregarLog("⚠️ ORDEN CANCELADA: Capital insuficiente")
                return@launch
            }

            val side = if (tipo == "LONG") "buy" else "sell"
            agregarLog("📤 ENVIANDO ORDEN: $side $sizeStr $symbol")

            val jsonBody = JSONObject()
            jsonBody.put("symbol", symbol)
            jsonBody.put("productType", "usdt-futures")
            jsonBody.put("marginCoin", "USDT")
            jsonBody.put("marginMode", "crossed")
            jsonBody.put("size", sizeStr)
            jsonBody.put("side", side)
            jsonBody.put("tradeSide", "open")
            jsonBody.put("orderType", "market")

            val response = BitgetUtils.authenticatedPost("/api/v2/mix/order/place-order", jsonBody.toString(), apiKey, apiSecret, apiPassphrase)

            if (response != null && response.contains("\"code\":\"00000\"")) {
                posicionAbierta = true
                monedaConPosicion = symbol
                tipoPosicion = tipo
                precioEntrada = precio
                maxPnLAlcanzado = 0.0
                ultimaOperacionTime = System.currentTimeMillis()

                agregarLog("✅ ORDEN CONFIRMADA")

                // 🔥 CAMBIO CLAVE: Avisar a la UI inmediatamente para que aparezca la tarjeta y el botón
                actualizarEstadoUI("🚀 POSICIÓN ABIERTA EN $symbol")

                enviarAlertaPush("REAL TRADE 🚀", "$tipo $symbol Ejecutado", false)
            } else {
                agregarLog("❌ ERROR BITGET: $response")
            }
        }
    }

    private fun cerrarTradeReal(motivo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            agregarLog("📤 CERRANDO POSICIÓN ($motivo)...")
            val sideCierre = if (tipoPosicion == "LONG") "sell" else "buy"

            val jsonBody = JSONObject()
            jsonBody.put("symbol", monedaConPosicion)
            jsonBody.put("productType", "usdt-futures")
            jsonBody.put("marginCoin", "USDT")
            jsonBody.put("marginMode", "crossed")
            jsonBody.put("side", sideCierre)
            jsonBody.put("tradeSide", "close")
            jsonBody.put("orderType", "market")
            jsonBody.put("size", "0")

            val response = BitgetUtils.authenticatedPost("/api/v2/mix/order/place-order", jsonBody.toString(), apiKey, apiSecret, apiPassphrase)

            if (response != null && response.contains("\"code\":\"00000\"")) {
                val nuevoSaldo = obtenerBalanceBitget()
                val pnlReal = if (nuevoSaldo > 0) nuevoSaldo - currentBalance else 0.0
                if (nuevoSaldo > 0) currentBalance = nuevoSaldo

                if (pnlReal > 0) { tradesGanados++; consecutiveLosses = 0 } else { tradesPerdidos++; consecutiveLosses++ }

                agregarLog("🏁 CIERRE: $motivo | PnL Real: $${"%.2f".format(pnlReal)}")

                // 🔥 CAMBIO CLAVE: Actualizar UI inmediatamente al cerrar para quitar la tarjeta
                posicionAbierta = false
                val mOld = monedaConPosicion
                monedaConPosicion = ""
                actualizarEstadoUI("✅ CERRADO: $mOld")

                enviarAlertaPush("CIERRE REAL 💰", "Saldo: $${"%.2f".format(currentBalance)}", pnlReal > 0)
            } else {
                agregarLog("❌ ERROR AL CERRAR: $response")
            }
        }
    }

    private fun sincronizarAjustes() {
        val prefs = getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        apiKey = prefs.getString("API_KEY", "") ?: ""
        apiSecret = prefs.getString("SECRET_KEY", "") ?: ""
        apiPassphrase = prefs.getString("API_PASSPHRASE", "") ?: ""
        targetTP = prefs.getString("TP_VAL", "2.0")?.toDoubleOrNull() ?: 2.0
        targetSL = prefs.getString("SL_VAL", "1.5")?.toDoubleOrNull() ?: 1.5
        leverage = prefs.getInt("LEVERAGE", 5).toDouble()
        val riskStr = prefs.getString("RISK_PERCENT", "20.0")?.replace(",", ".") ?: "20.0"
        riskPercent = riskStr.toDoubleOrNull() ?: 20.0
        activeStrategy = prefs.getString("STRATEGY", "MODERADA") ?: "MODERADA"

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

    private suspend fun obtenerBalanceBitget(): Double = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) return@withContext 0.0
        val path = "/api/v2/mix/account/accounts?productType=USDT-FUTURES"
        val jsonStr = BitgetUtils.authenticatedGet(path, apiKey, apiSecret, apiPassphrase)
        try {
            if (jsonStr != null) {
                val json = JSONObject(jsonStr)
                if (json.getString("code") == "00000") {
                    val data = json.getJSONArray("data")
                    for (i in 0 until data.length()) {
                        val asset = data.getJSONObject(i)
                        if (asset.getString("marginCoin") == "USDT") return@withContext asset.getString("available").toDouble()
                    }
                }
            }
        } catch (e: Exception) {}
        return@withContext 0.0
    }

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

    private fun calcularPnL(actual: Double): Double {
        return when (tipoPosicion) {
            "LONG" -> ((actual - precioEntrada) / precioEntrada) * 100 * leverage
            "SHORT" -> ((precioEntrada - actual) / precioEntrada) * 100 * leverage
            else -> 0.0
        }
    }

    private fun actualizarEstadoUI(mensaje: String) {
        currentStatus = mensaje
        val intent = Intent("ACTUALIZACION_TRADING")
        intent.putExtra("STATUS_MSG", mensaje)
        intent.putExtra("BALANCE", currentBalance)
        intent.putExtra("IS_TRADE_OPEN", posicionAbierta)

        if (posicionAbierta) {
            val pnlPorcentaje = calcularPnL(lastPrice.replace(",", ".").toDoubleOrNull() ?: precioEntrada)
            val pnlDolares = (pnlPorcentaje / 100) * (currentBalance * (riskPercent / 100.0))

            intent.putExtra("TRADE_SYMBOL", monedaConPosicion)
            intent.putExtra("TRADE_TYPE", tipoPosicion)
            intent.putExtra("TRADE_PNL", pnlPorcentaje)
            intent.putExtra("TRADE_PNL_CASH", pnlDolares)
        }
        sendBroadcast(intent)
    }

    private fun agregarLog(mensaje: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val linea = "[$timestamp] $mensaje\n"
        android.util.Log.d("TradingBot", mensaje)
        logHistory.insert(0, linea)
        sendBroadcast(Intent("ACTUALIZACION_TRADING").putExtra("LOG_MSG", linea))
    }

    private fun actualizarMemoriaCache(symbol: String, rsi: Double, vol: Double, trendUp: Boolean, precio: Double) {
        lastRSI = "%.0f".format(rsi); lastVol = "%.1f%%".format(vol); lastTrend = if (trendUp) "LONG ↗" else "SHORT ↘"; lastPrice = "%.2f".format(precio); symbolBeingScanned = symbol
    }

    private fun actualizarDatos(symbol: String, precio: Double, volumen: Double) {
        val listaP = historialPreciosMap[symbol] ?: return
        val listaV = historialVolumenMap[symbol] ?: return
        listaP.add(precio); listaV.add(volumen)
        if (listaP.size > 100) { listaP.removeAt(0); listaV.removeAt(0) }
    }

    private fun crearCanalesNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID_FOREGROUND, "Motor SignalFusion", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID_ALERTS, "Alertas Trading", NotificationManager.IMPORTANCE_HIGH))
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
        if (isReceiverRegistered) {
            try { unregisterReceiver(manualCloseReceiver) } catch (e: Exception) {}
        }
        super.onDestroy()
    }
}