package com.example.bitgetbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial

class BotFragment : Fragment() {

    private var tvLivePrice: TextView? = null
    private var tvTotalBalance: TextView? = null
    private var tvDailyPnL: TextView? = null
    private var tvSystemStatus: TextView? = null
    private var switchBotActive: SwitchMaterial? = null

    // Antena para recibir datos del Bot
    private val tradingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) {
                // 1. Precio
                val priceMsg = intent.getStringExtra("PRICE_MSG")
                if (priceMsg != null) tvLivePrice?.text = priceMsg

                // 2. Saldo (Solo si el bot manda una actualización mientras opera)
                val nuevoSaldo = intent.getDoubleExtra("BALANCE_MSG", -1.0)
                if (nuevoSaldo != -1.0) {
                    actualizarTextoSaldo(nuevoSaldo)
                }

                // 3. Estado
                val statusMsg = intent.getStringExtra("STATUS_MSG")
                if (statusMsg != null) {
                    if (statusMsg.contains("Escaneando") || statusMsg.contains("GANANDO")) {
                        tvSystemStatus?.text = "● ONLINE"
                        tvSystemStatus?.setTextColor(Color.GREEN)
                        if (switchBotActive?.isChecked == false) {
                            switchBotActive?.setOnCheckedChangeListener(null)
                            switchBotActive?.isChecked = true
                            configurarSwitch()
                        }
                    } else {
                        tvSystemStatus?.text = "● $statusMsg"
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_bot, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvLivePrice = view.findViewById(R.id.tvLivePrice)
        tvTotalBalance = view.findViewById(R.id.tvTotalBalance)
        tvDailyPnL = view.findViewById(R.id.tvDailyPnL)
        tvSystemStatus = view.findViewById(R.id.tvSystemStatus)
        switchBotActive = view.findViewById(R.id.switchBotActive)

        configurarSwitch()

        // Recuperar estado del botón
        if (TradingService.isRunning) {
            tvSystemStatus?.text = "● ONLINE"
            tvSystemStatus?.setTextColor(Color.GREEN)
            switchBotActive?.isChecked = true
        } else {
            tvSystemStatus?.text = "● OFFLINE"
            tvSystemStatus?.setTextColor(Color.GRAY)
            switchBotActive?.isChecked = false
        }
    }

    override fun onResume() {
        super.onResume()

        // Sincronizar saldo y estado actual de la memoria estática
        tvTotalBalance?.text = "$%.2f".format(TradingService.currentBalance)
        tvLivePrice?.text = TradingService.lastPrice

        // Actualizar el estado visual (ONLINE/INICIANDO)
        tvSystemStatus?.text = "● ${TradingService.currentStatus}"

        // Reconectar receptor...
        val filter = IntentFilter("ACTUALIZACION_TRADING")
        ContextCompat.registerReceiver(requireContext(), tradingReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        try { requireContext().unregisterReceiver(tradingReceiver) } catch (e: Exception) {}
    }

    // Función auxiliar para pintar el saldo y el porcentaje
    private fun actualizarTextoSaldo(saldo: Double) {
        tvTotalBalance?.text = "$%.2f".format(saldo)

        // Calculamos cuánto ha cambiado respecto a la base (ej: 1000)
        // Truco: Leemos la base "original" desde Settings para que el % sea real
        val sharedPref = requireActivity().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        val inversionInicial = sharedPref.getString("AMOUNT", "1000")?.toDoubleOrNull() ?: 1000.0

        // Evitamos división por cero
        if (inversionInicial > 0) {
            val pnl = ((saldo - inversionInicial) / inversionInicial) * 100
            tvDailyPnL?.text = "%+.2f%%".format(pnl)

            if (pnl >= 0) tvDailyPnL?.setTextColor(Color.GREEN)
            else tvDailyPnL?.setTextColor(Color.RED)
        }
    }

    private fun configurarSwitch() {
        switchBotActive?.setOnCheckedChangeListener { _, isChecked ->
            val context = requireContext()
            if (isChecked) {
                tvSystemStatus?.text = "● INICIANDO..."
                tvSystemStatus?.setTextColor(Color.YELLOW)

                val intentService = Intent(context, TradingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intentService)
                } else {
                    context.startService(intentService)
                }
            } else {
                val intentService = Intent(context, TradingService::class.java)
                context.stopService(intentService)
                tvSystemStatus?.text = "● OFFLINE"
                tvSystemStatus?.setTextColor(Color.GRAY)
            }
        }
    }
}