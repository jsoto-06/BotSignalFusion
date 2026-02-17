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

        // 🟢 ESTADO GLOBAL (Para que la UI lo lea siempre)
        var posicionAbierta = false
        var monedaConPosicion = ""
        var tipoPosicion = ""
        var precioEntrada = 0.0

        // Caché UI
        var lastPrice = "0.0"
        var lastRSI = "--"
        var currentStatus = "Iniciando..."
    }

    private val client = OkHttpClient()
    private var job: Job? = null
    private val CHANNEL_ID_FOREGROUND = "SignalFusionChannel"
    private lateinit var manualCloseReceiver: BroadcastReceiver
    private var isReceiverRegistered = false

    // Ajustes
    private var targetTP = 2.0
    private var targetSL = 1.5
    private var leverage = 5.0
    private var riskPercent = 20.0
    private var activeStrategy = "MODERADA"
    private var apiKey = ""
    private var apiSecret = ""
    private var apiPassphrase = ""

    private var activeSymbols = mutableListOf<String>()
    private var currentSymbolIndex = 0
    private val historialPreciosMap = mutableMapOf<String, MutableList<Double>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanalesNotificacion()

        manualCloseReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "FORZAR_CIERRE_MANUAL" && posicionAbierta) {
                    CoroutineScope(Dispatchers.IO).launch { cerrarTradeReal("Manual Panic") }
                }
                // 🔥 NUEVO: Responder a peticiones de "Refresh" de la UI
                if (intent?.action == "SOLICITAR_REFRESH_UI") {
                    actualizarEstadoUI("Sincronizado")
                }
            }
        }
        val filter = IntentFilter()
        filter.addAction("FORZAR_CIERRE_MANUAL")
        filter.addAction("SOLICITAR_REFRESH_UI")
        ContextCompat.registerReceiver(this, manualCloseReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        isReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sincronizarAjustes()
        CoroutineScope(Dispatchers.IO).launch {
            val saldo = obtenerBalanceBitget()
            if (saldo != null) {
                currentBalance = saldo
                isRunning = true
                actualizarEstadoUI("Buscando entradas...")
                iniciarCicloTrading()
            }
        }
        startForeground(1, createForegroundNotification("SignalFusion Pro", "Escáner Activo"))
        return START_STICKY
    }

    private fun iniciarCicloTrading() {
        job = CoroutineScope(Dispatchers.IO).launch {
            activeSymbols.forEach { descargarHistorialInicial(it); delay(500) }
            while (isActive) {
                val symbol = if (posicionAbierta) monedaConPosicion else activeSymbols[currentSymbolIndex]
                val precio = obtenerPrecioReal(symbol)

                if (precio > 0) {
                    lastPrice = precio.toString()
                    actualizarDatos(symbol, precio)
                    val hP = historialPreciosMap[symbol] ?: mutableListOf()

                    if (hP.size >= 50) {
                        if (!posicionAbierta) {
                            ejecutarEstrategia(symbol, precio, hP)
                        } else {
                            gestionarSalida(symbol, precio)
                        }
                    }
                }
                if (!posicionAbierta) currentSymbolIndex = (currentSymbolIndex + 1) % activeSymbols.size
                actualizarEstadoUI(if (posicionAbierta) "Operando $symbol" else "Escaneando...")
                delay(4000)
            }
        }
    }

    private fun ejecutarEstrategia(symbol: String, precio: Double, hP: List<Double>) {
        val rsi = Indicadores.calcularRSI(hP, 14)
        lastRSI = rsi.toInt().toString()

        if (activeStrategy == "AGRESIVA") {
            if (rsi < 40) abrirTradeReal(symbol, precio, "LONG")
            else if (rsi > 60) abrirTradeReal(symbol, precio, "SHORT")
        } else {
            val ema9 = Indicadores.calcularEMA(hP, 9)
            val ema21 = Indicadores.calcularEMA(hP, 21)
            if (rsi < 35 && ema9 > ema21) abrirTradeReal(symbol, precio, "LONG")
            else if (rsi > 65 && ema9 < ema21) abrirTradeReal(symbol, precio, "SHORT")
        }
    }

    private fun gestionarSalida(symbol: String, precio: Double) {
        val pnl = calcularPnL(precio)
        if (pnl >= targetTP) cerrarTradeReal("Take Profit ✅")
        else if (pnl <= -targetSL) cerrarTradeReal("Stop Loss 🛑")
    }

    private fun abrirTradeReal(symbol: String, precio: Double, tipo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            // 1. Set Leverage
            val levBody = JSONObject().apply {
                put("symbol", symbol); put("productType", "usdt-futures")
                put("marginCoin", "USDT"); put("leverage", leverage.toInt().toString())
                put("holdSide", if (tipo == "LONG") "long" else "short")
            }
            BitgetUtils.authenticatedPost("/api/v2/mix/account/set-leverage", levBody.toString(), apiKey, apiSecret, apiPassphrase)

            // 2. Place Order
            val margenUsdt = currentBalance * (riskPercent / 100.0)
            val sizeAmount = (margenUsdt * leverage) / precio
            val sizeStr = String.format(Locale.US, if (symbol.contains("XRP")) "%.0f" else "%.1f", sizeAmount)

            val jsonBody = JSONObject().apply {
                put("symbol", symbol); put("productType", "usdt-futures")
                put("marginCoin", "USDT"); put("marginMode", "crossed")
                put("side", if (tipo == "LONG") "buy" else "sell")
                put("tradeSide", "open"); put("orderType", "market"); put("size", sizeStr)
            }

            val resp = BitgetUtils.authenticatedPost("/api/v2/mix/order/place-order", jsonBody.toString(), apiKey, apiSecret, apiPassphrase)
            if (resp != null && resp.contains("00000")) {
                posicionAbierta = true
                monedaConPosicion = symbol
                tipoPosicion = tipo
                precioEntrada = precio
                agregarLog("🚀 COMPRA: $tipo $symbol")
                actualizarEstadoUI("POSICIÓN ABIERTA")
            }
        }
    }

    private fun cerrarTradeReal(motivo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val jsonBody = JSONObject().apply {
                put("symbol", monedaConPosicion); put("productType", "usdt-futures")
                put("marginCoin", "USDT"); put("marginMode", "crossed")
                put("side", if (tipoPosicion == "LONG") "sell" else "buy")
                put("tradeSide", "close"); put("orderType", "market"); put("size", "0")
            }
            val resp = BitgetUtils.authenticatedPost("/api/v2/mix/order/place-order", jsonBody.toString(), apiKey, apiSecret, apiPassphrase)
            if (resp != null && resp.contains("00000")) {
                posicionAbierta = false
                val m = monedaConPosicion
                monedaConPosicion = ""
                obtenerBalanceBitget()?.let { currentBalance = it }
                agregarLog("🏁 $motivo en $m")
                actualizarEstadoUI("Buscando señales...")
            }
        }
    }

    private fun actualizarEstadoUI(mensaje: String) {
        val intent = Intent("ACTUALIZACION_TRADING")
        intent.putExtra("STATUS_MSG", mensaje)
        intent.putExtra("BALANCE", currentBalance)
        intent.putExtra("IS_TRADE_OPEN", posicionAbierta)

        if (posicionAbierta) {
            val pnl = calcularPnL(lastPrice.toDoubleOrNull() ?: precioEntrada)
            intent.putExtra("TRADE_SYMBOL", monedaConPosicion)
            intent.putExtra("TRADE_TYPE", tipoPosicion)
            intent.putExtra("TRADE_PNL", pnl)
        }
        sendBroadcast(intent)
    }

    private fun calcularPnL(actual: Double): Double {
        if (precioEntrada <= 0) return 0.0
        val diff = if (tipoPosicion == "LONG") actual - precioEntrada else precioEntrada - actual
        return (diff / precioEntrada) * 100 * leverage
    }

    private fun sincronizarAjustes() {
        val prefs = getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        apiKey = prefs.getString("API_KEY", "") ?: ""
        apiSecret = prefs.getString("SECRET_KEY", "") ?: ""
        apiPassphrase = prefs.getString("API_PASSPHRASE", "") ?: ""
        leverage = prefs.getInt("LEVERAGE", 5).toDouble()
        riskPercent = prefs.getString("RISK_PERCENT", "20.0")?.toDoubleOrNull() ?: 20.0
        activeStrategy = prefs.getString("STRATEGY", "MODERADA") ?: "MODERADA"

        activeSymbols.clear()
        if (prefs.getBoolean("COIN_BTC", true)) activeSymbols.add("BTCUSDT")
        if (prefs.getBoolean("COIN_ETH", false)) activeSymbols.add("ETHUSDT")
        if (prefs.getBoolean("COIN_SOL", false)) activeSymbols.add("SOLUSDT")
        if (prefs.getBoolean("COIN_XRP", false)) activeSymbols.add("XRPUSDT")
    }

    private suspend fun obtenerBalanceBitget(): Double? = withContext(Dispatchers.IO) {
        val jsonStr = BitgetUtils.authenticatedGet("/api/v2/mix/account/accounts?productType=USDT-FUTURES", apiKey, apiSecret, apiPassphrase)
        try {
            val data = JSONObject(jsonStr).getJSONArray("data")
            for (i in 0 until data.length()) {
                val asset = data.getJSONObject(i)
                if (asset.getString("marginCoin") == "USDT") return@withContext asset.getString("available").toDouble()
            }
        } catch (e: Exception) {}
        null
    }

    private suspend fun obtenerPrecioReal(s: String): Double = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.bitget.com/api/v2/mix/market/ticker?symbol=$s&productType=USDT-FUTURES"
            val resp = client.newCall(Request.Builder().url(url).build()).execute().body?.string()
            JSONObject(resp).getJSONArray("data").getJSONObject(0).getString("lastPr").toDouble()
        } catch (e: Exception) { 0.0 }
    }

    private suspend fun descargarHistorialInicial(s: String) = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.bitget.com/api/v2/mix/market/candles?symbol=$s&productType=USDT-FUTURES&granularity=1m&limit=100"
            val resp = client.newCall(Request.Builder().url(url).build()).execute().body?.string()
            val data = JSONObject(resp).getJSONArray("data")
            val precios = mutableListOf<Double>()
            for (i in 0 until data.length()) { precios.add(data.getJSONArray(i).getString(4).toDouble()) }
            historialPreciosMap[s] = precios
        } catch (e: Exception) {}
    }

    private fun actualizarDatos(s: String, p: Double) {
        val lista = historialPreciosMap[s] ?: return
        lista.add(p); if (lista.size > 100) lista.removeAt(0)
    }

    private fun crearCanalesNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID_FOREGROUND, "Motor", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun createForegroundNotification(t: String, c: String) = NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
        .setContentTitle(t).setContentText(c).setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true).build()

    private fun agregarLog(m: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logHistory.insert(0, "[$time] $m\n")
        sendBroadcast(Intent("ACTUALIZACION_TRADING").putExtra("LOG_MSG", "[$time] $m\n"))
    }

    override fun onDestroy() {
        isRunning = false; job?.cancel()
        if (isReceiverRegistered) unregisterReceiver(manualCloseReceiver)
        super.onDestroy()
    }
}