package com.example.signalfusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.SimpleDateFormat
import java.util.*

class BotFragment : Fragment() {

    private lateinit var tvLivePrice: TextView
    private lateinit var tvTotalBalance: TextView
    private lateinit var txtRSI: TextView
    private lateinit var txtVolatilidad: TextView
    private lateinit var txtTendencia: TextView
    private lateinit var txtEstadoDetalle: TextView
    private lateinit var txtEstadoSimple: TextView
    private lateinit var switchBotActive: MaterialSwitch
    private lateinit var chipStatus: Chip
    private lateinit var tvSaludBot: TextView

    // 🆕 FASE 2: Vistas de la Tarjeta Activa
    private lateinit var cardActiveTrade: MaterialCardView
    private lateinit var tvTradeTime: TextView
    private lateinit var tvTradeSymbol: TextView
    private lateinit var tvTradeType: TextView
    private lateinit var tvTradePnL: TextView
    private lateinit var btnCloseTrade: Button

    // 🆕 FASE 2: Temporizador para la tarjeta
    private var handlerTime = Handler(Looper.getMainLooper())
    private var updateTimeRunnable: Runnable? = null

    private val tradingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTUALIZACION_TRADING") {
                val statusMsg = intent.getStringExtra("STATUS_MSG")
                val logMsg = intent.getStringExtra("LOG_MSG")
                val isTradeOpen = intent.getBooleanExtra("IS_TRADE_OPEN", false)
                val tradeSymbol = intent.getStringExtra("TRADE_SYMBOL") ?: ""
                val tradeType = intent.getStringExtra("TRADE_TYPE") ?: ""
                val tradePnl = intent.getDoubleExtra("TRADE_PNL", 0.0)
                val tradeStartTime = intent.getLongExtra("TRADE_START_TIME", 0L)

                // Actualizar Logs y Estado
                if (logMsg != null) {
                    txtEstadoDetalle.text = "> ${logMsg.substringAfter("] ")}"
                } else if (statusMsg != null) {
                    txtEstadoDetalle.text = "> $statusMsg"
                    parsearDatosTrading(statusMsg)
                }

                if (TradingService.lastPrice != "Conectando...") {
                    tvLivePrice.text = "$${TradingService.lastPrice}"
                }

                if (TradingService.isRunning) {
                    tvTotalBalance.text = "$%.2f".format(TradingService.currentBalance)
                }

                val trades = intent.getIntExtra("TOTAL_TRADES", 0)
                val wins = intent.getIntExtra("WINS", 0)
                val balance = intent.getDoubleExtra("BALANCE", TradingService.currentBalance)
                val initial = intent.getDoubleExtra("INITIAL_BALANCE", TradingService.currentBalance)

                actualizarSalud(trades, wins, balance, initial)

                // 🆕 FASE 2: Lógica de la Tarjeta Activa
                if (isTradeOpen) {
                    mostrarTarjetaActiva(tradeSymbol, tradeType, tradePnl, tradeStartTime)
                } else {
                    ocultarTarjetaActiva()
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_bot, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Vincular vistas generales
        tvLivePrice = view.findViewById(R.id.tvLivePrice)
        tvTotalBalance = view.findViewById(R.id.tvTotalBalance)
        txtRSI = view.findViewById(R.id.txtRSI)
        txtVolatilidad = view.findViewById(R.id.txtVolatilidad)
        txtTendencia = view.findViewById(R.id.txtTendencia)
        txtEstadoDetalle = view.findViewById(R.id.txtEstadoDetalle)
        txtEstadoSimple = view.findViewById(R.id.txtEstadoSimple)
        switchBotActive = view.findViewById(R.id.switchBotActive)
        chipStatus = view.findViewById(R.id.chipStatus)
        tvSaludBot = view.findViewById(R.id.tvSaludBot)

        // 🆕 FASE 2: Vincular vistas de la tarjeta activa
        cardActiveTrade = view.findViewById(R.id.cardActiveTrade)
        tvTradeTime = view.findViewById(R.id.tvTradeTime)
        tvTradeSymbol = view.findViewById(R.id.tvTradeSymbol)
        tvTradeType = view.findViewById(R.id.tvTradeType)
        tvTradePnL = view.findViewById(R.id.tvTradePnL)
        btnCloseTrade = view.findViewById(R.id.btnCloseTrade)

        switchBotActive.isChecked = TradingService.isRunning
        actualizarUIEstado(TradingService.isRunning)

        switchBotActive.setOnCheckedChangeListener { _, isChecked ->
            val context = requireContext()
            val intent = Intent(context, TradingService::class.java)
            if (isChecked) {
                actualizarUIEstado(true)
                ContextCompat.startForegroundService(context, intent)
            } else {
                actualizarUIEstado(false)
                context.stopService(intent)
            }
        }

        // 🆕 FASE 2: Botón de Pánico (Cierre Manual)
        btnCloseTrade.setOnClickListener {
            // Enviamos un mensaje al servicio para forzar el cierre
            val intent = Intent("FORZAR_CIERRE_MANUAL")
            requireContext().sendBroadcast(intent)

            // Efecto visual inmediato
            btnCloseTrade.text = "CERRANDO..."
            btnCloseTrade.isEnabled = false
        }
    }

    // 🆕 FASE 2: Funciones para la Tarjeta
    private fun mostrarTarjetaActiva(symbol: String, type: String, pnl: Double, startTime: Long) {
        cardActiveTrade.visibility = View.VISIBLE
        tvTradeSymbol.text = symbol
        tvTradeType.text = if (type == "LONG") "LONG ↗" else "SHORT ↘"
        tvTradeType.setTextColor(ContextCompat.getColor(requireContext(), if (type == "LONG") R.color.neon_green else R.color.neon_red))

        // Formatear PnL
        val pnlText = "%+.2f%%".format(pnl)
        tvTradePnL.text = pnlText
        if (pnl > 0) {
            tvTradePnL.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_green))
        } else {
            tvTradePnL.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_red))
        }

        btnCloseTrade.text = "CERRAR POSICIÓN (PÁNICO)"
        btnCloseTrade.isEnabled = true

        // Iniciar el cronómetro si no está corriendo
        if (updateTimeRunnable == null) {
            updateTimeRunnable = object : Runnable {
                override fun run() {
                    val elapsedMillis = System.currentTimeMillis() - startTime
                    val minutes = (elapsedMillis / 1000) / 60
                    val seconds = (elapsedMillis / 1000) % 60
                    tvTradeTime.text = String.format("%02d:%02d", minutes, seconds)
                    handlerTime.postDelayed(this, 1000)
                }
            }
            handlerTime.post(updateTimeRunnable!!)
        }
    }

    private fun ocultarTarjetaActiva() {
        cardActiveTrade.visibility = View.GONE
        updateTimeRunnable?.let { handlerTime.removeCallbacks(it) }
        updateTimeRunnable = null
    }

    private fun actualizarSalud(totalTrades: Int, wins: Int, currentBalance: Double, initialBalance: Double) {
        if (totalTrades == 0) {
            tvSaludBot.text = "🔵 CALIBRANDO..."
            tvSaludBot.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_grey))
            return
        }

        val baseBalance = if (initialBalance > 0) initialBalance else 1000.0
        val winRate = (wins.toDouble() / totalTrades.toDouble()) * 100
        val roi = ((currentBalance - baseBalance) / baseBalance) * 100

        val (emoji, texto, colorId) = when {
            roi > 0.5 && winRate >= 50 -> Triple("🟢", "EXCELENTE", R.color.neon_green)
            roi > 0.0 -> Triple("🟡", "POSITIVO", R.color.neon_gold)
            roi > -2.0 -> Triple("🟠", "REGULAR", R.color.neon_gold)
            else -> Triple("🔴", "CRÍTICO", R.color.neon_red)
        }

        tvSaludBot.text = "$emoji $texto (WR: %.0f%% | ROI: %+.1f%%)".format(winRate, roi)
        tvSaludBot.setTextColor(ContextCompat.getColor(requireContext(), colorId))
    }

    override fun onResume() {
        super.onResume()

        val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        val saldoConfigurado = prefs.getString("AMOUNT", "1000")?.toDoubleOrNull() ?: 1000.0

        if (TradingService.isRunning) {
            actualizarUIEstado(true)
            tvTotalBalance.text = "$%.2f".format(TradingService.currentBalance)

            txtRSI.text = TradingService.lastRSI
            txtVolatilidad.text = TradingService.lastVol
            txtTendencia.text = TradingService.lastTrend
            tvLivePrice.text = "$${TradingService.lastPrice}"

            if (TradingService.currentStatus != "Iniciando...") {
                txtEstadoDetalle.text = "> ${TradingService.currentStatus}"
            }
            colorearDatos(TradingService.lastRSI, TradingService.lastTrend)

            actualizarSalud(TradingService.totalTrades, TradingService.tradesGanados, TradingService.currentBalance, saldoConfigurado)

            // 🆕 Verificar si hay posición abierta al volver a la app
            val statePrefs = requireContext().getSharedPreferences("BotState", Context.MODE_PRIVATE)
            val isPosOpen = statePrefs.getBoolean("POSICION_ABIERTA", false)
            if (isPosOpen) {
                // Recuperar datos temporales para mostrar la tarjeta mientras llega la actualización del servicio
                val sym = statePrefs.getString("MONEDA_POSICION", "???") ?: "???"
                val type = statePrefs.getString("TIPO_POSICION", "???") ?: "???"
                val time = statePrefs.getLong("ULTIMA_OP_TIME", System.currentTimeMillis())
                mostrarTarjetaActiva(sym, type, 0.0, time)
            } else {
                ocultarTarjetaActiva()
            }

        } else {
            actualizarUIEstado(false)
            tvTotalBalance.text = "$%.2f".format(saldoConfigurado)
            ocultarTarjetaActiva()
        }

        val filter = IntentFilter("ACTUALIZACION_TRADING")
        ContextCompat.registerReceiver(requireContext(), tradingReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        try { requireContext().unregisterReceiver(tradingReceiver) } catch (e: Exception) {}
        // Detener cronómetro
        updateTimeRunnable?.let { handlerTime.removeCallbacks(it) }
        updateTimeRunnable = null
    }

    private fun parsearDatosTrading(msg: String) {
        if (msg.contains("RSI:")) {
            val rsiVal = msg.substringAfter("RSI:").substringBefore("|").trim()
            txtRSI.text = rsiVal
            colorearDatos(rsiVal, if (msg.contains("Alc") || msg.contains("LONG")) "LONG" else "SHORT")
        }
        if (msg.contains("Vol:")) {
            txtVolatilidad.text = msg.substringAfter("Vol:").substringBefore("|").replace(Regex("[^0-9.%]"), "")
        }
        if (msg.contains("Alc") || msg.contains("LONG")) txtTendencia.text = "LONG ↗"
        else if (msg.contains("Baj") || msg.contains("SHORT")) txtTendencia.text = "SHORT ↘"
    }

    private fun colorearDatos(rsiStr: String, trend: String) {
        val rsi = rsiStr.toDoubleOrNull() ?: 50.0
        if (rsi > 60) txtRSI.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_red))
        else if (rsi < 40) txtRSI.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_green))
        else txtRSI.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_gold))

        if (trend.contains("LONG")) {
            txtTendencia.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_green))
        } else {
            txtTendencia.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_red))
        }
    }

    private fun actualizarUIEstado(activo: Boolean) {
        switchBotActive.isChecked = activo
        if (activo) {
            chipStatus.text = "● ONLINE"
            chipStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_green))
            txtEstadoSimple.text = "Escaneando..."
            txtEstadoSimple.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_green))
        } else {
            chipStatus.text = "● OFFLINE"
            chipStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_grey))
            txtEstadoSimple.text = "Detenido"
            txtEstadoSimple.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_grey))
            txtRSI.text = "--"
            txtVolatilidad.text = "--"
            txtTendencia.text = "--"

            tvSaludBot.text = "⚪ OFFLINE"
            tvSaludBot.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_grey))
            ocultarTarjetaActiva()
        }
    }
}