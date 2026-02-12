package com.example.signalfusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch

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

    // 🆕 NUEVO: Variable para la salud del bot
    private lateinit var tvSaludBot: TextView

    private val tradingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTUALIZACION_TRADING") {
                val statusMsg = intent.getStringExtra("STATUS_MSG")
                val logMsg = intent.getStringExtra("LOG_MSG")

                // Lógica de visualización de logs/estado
                if (logMsg != null) {
                    txtEstadoDetalle.text = "> ${logMsg.substringAfter("] ")}"
                } else if (statusMsg != null) {
                    txtEstadoDetalle.text = "> $statusMsg"
                    parsearDatosTrading(statusMsg)
                }

                // Actualizar precio
                if (TradingService.lastPrice != "Conectando...") {
                    tvLivePrice.text = "$${TradingService.lastPrice}"
                }

                // ACTUALIZACIÓN EN TIEMPO REAL DEL SALDO
                if (TradingService.isRunning) {
                    tvTotalBalance.text = "$%.2f".format(TradingService.currentBalance)
                }

                // 🆕 NUEVO: Recibir métricas y actualizar Salud
                val trades = intent.getIntExtra("TOTAL_TRADES", 0)
                val wins = intent.getIntExtra("WINS", 0)
                // Usamos valores por defecto seguros para evitar crashes
                val balance = intent.getDoubleExtra("BALANCE", TradingService.currentBalance)
                val initial = intent.getDoubleExtra("INITIAL_BALANCE", TradingService.currentBalance) // Si es 0, usa el actual

                actualizarSalud(trades, wins, balance, initial)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_bot, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvLivePrice = view.findViewById(R.id.tvLivePrice)
        tvTotalBalance = view.findViewById(R.id.tvTotalBalance)
        txtRSI = view.findViewById(R.id.txtRSI)
        txtVolatilidad = view.findViewById(R.id.txtVolatilidad)
        txtTendencia = view.findViewById(R.id.txtTendencia)
        txtEstadoDetalle = view.findViewById(R.id.txtEstadoDetalle)
        txtEstadoSimple = view.findViewById(R.id.txtEstadoSimple)
        switchBotActive = view.findViewById(R.id.switchBotActive)
        chipStatus = view.findViewById(R.id.chipStatus)

        // 🆕 Vincular la vista (Asegúrate que añadiste el ID en el XML antes)
        tvSaludBot = view.findViewById(R.id.tvSaludBot)

        // Estado inicial del Switch
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
    }

    // 🆕 NUEVO: Lógica del Semáforo de Salud
    private fun actualizarSalud(totalTrades: Int, wins: Int, currentBalance: Double, initialBalance: Double) {
        if (totalTrades == 0) {
            tvSaludBot.text = "🔵 CALIBRANDO..."
            tvSaludBot.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_grey))
            return
        }

        // Evitar división por cero
        val baseBalance = if (initialBalance > 0) initialBalance else 1000.0

        // Cálculos Matemáticos
        val winRate = (wins.toDouble() / totalTrades.toDouble()) * 100
        val roi = ((currentBalance - baseBalance) / baseBalance) * 100

        // Lógica de Colores (Semáforo)
        val (emoji, texto, colorId) = when {
            roi > 0.5 && winRate >= 50 -> Triple("🟢", "EXCELENTE", R.color.neon_green)
            roi > 0.0 -> Triple("🟡", "POSITIVO", R.color.neon_gold)
            roi > -2.0 -> Triple("🟠", "REGULAR", R.color.neon_gold) // Usamos Gold si no tienes Orange definido
            else -> Triple("🔴", "CRÍTICO", R.color.neon_red)
        }

        tvSaludBot.text = "$emoji $texto (WR: %.0f%% | ROI: %+.1f%%)".format(winRate, roi)
        tvSaludBot.setTextColor(ContextCompat.getColor(requireContext(), colorId))
    }

    override fun onResume() {
        super.onResume()

        // 1. Leemos la configuración guardada
        val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        val saldoConfigurado = prefs.getString("AMOUNT", "500")?.toDoubleOrNull() ?: 500.0

        if (TradingService.isRunning) {
            actualizarUIEstado(true)
            tvTotalBalance.text = "$%.2f".format(TradingService.currentBalance)

            // Datos de memoria
            txtRSI.text = TradingService.lastRSI
            txtVolatilidad.text = TradingService.lastVol
            txtTendencia.text = TradingService.lastTrend
            tvLivePrice.text = "$${TradingService.lastPrice}"

            if (TradingService.currentStatus != "Iniciando...") {
                txtEstadoDetalle.text = "> ${TradingService.currentStatus}"
            }
            colorearDatos(TradingService.lastRSI, TradingService.lastTrend)

            // 🆕 Actualizar salud al volver (usando datos estáticos temporales)
            actualizarSalud(TradingService.totalTrades, TradingService.tradesGanados, TradingService.currentBalance, saldoConfigurado)

        } else {
            actualizarUIEstado(false)
            tvTotalBalance.text = "$%.2f".format(saldoConfigurado)
        }

        val filter = IntentFilter("ACTUALIZACION_TRADING")
        ContextCompat.registerReceiver(requireContext(), tradingReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        try { requireContext().unregisterReceiver(tradingReceiver) } catch (e: Exception) {}
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
            chipStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_grey)) // Cambiado a text_grey si text_secondary falla
            txtEstadoSimple.text = "Detenido"
            txtEstadoSimple.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_grey))
            txtRSI.text = "--"
            txtVolatilidad.text = "--"
            txtTendencia.text = "--"

            // 🆕 Resetear salud
            tvSaludBot.text = "⚪ OFFLINE"
            tvSaludBot.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_grey))
        }
    }
}