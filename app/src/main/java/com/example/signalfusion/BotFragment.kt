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

                // ACTUALIZACIÓN EN TIEMPO REAL DEL SALDO (Si el bot gana/pierde)
                if (TradingService.isRunning) {
                    tvTotalBalance.text = "$%.2f".format(TradingService.currentBalance)
                }
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

    override fun onResume() {
        super.onResume()

        // --- AQUÍ ESTÁ EL ARREGLO PARA QUE MUESTRE 1000 ---

        // 1. Leemos la configuración guardada SIEMPRE
        val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        val saldoConfigurado = prefs.getString("AMOUNT", "500")?.toDoubleOrNull() ?: 500.0

        if (TradingService.isRunning) {
            actualizarUIEstado(true)

            // Si el bot corre, usamos el saldo dinámico del servicio
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

        } else {
            actualizarUIEstado(false)
            // Si el bot está apagado, mostramos lo que guardaste en Ajustes (1000)
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
            chipStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            txtEstadoSimple.text = "Detenido"
            txtEstadoSimple.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            txtRSI.text = "--"
            txtVolatilidad.text = "--"
            txtTendencia.text = "--"
        }
    }
}