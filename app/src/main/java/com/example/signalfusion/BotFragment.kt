package com.example.signalfusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
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

class BotFragment : Fragment() {

    private lateinit var tvLivePrice: TextView
    private lateinit var tvTotalBalance: TextView
    private lateinit var txtRSI: TextView
    private lateinit var txtVolatilidad: TextView
    private lateinit var txtTendencia: TextView
    private lateinit var switchBotActive: MaterialSwitch
    private lateinit var chipStatus: Chip

    private lateinit var cardActiveTrade: MaterialCardView
    private lateinit var tvTradeSymbol: TextView
    private lateinit var tvTradeType: TextView
    private lateinit var tvTradePnL: TextView
    private lateinit var btnCloseTrade: Button

    private val tradingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTUALIZACION_TRADING") {
                val isTradeOpen = intent.getBooleanExtra("IS_TRADE_OPEN", false)

                // 1. Datos Generales
                val balance = intent.getDoubleExtra("BALANCE", TradingService.currentBalance)
                tvTotalBalance.text = "$%.2f".format(balance)
                tvLivePrice.text = "$${TradingService.lastPrice}"
                txtRSI.text = TradingService.lastRSI
                colorearRSI(TradingService.lastRSI)

                // 2. Sincronizar Tarjeta
                if (isTradeOpen) {
                    val symbol = intent.getStringExtra("TRADE_SYMBOL") ?: "---"
                    val type = intent.getStringExtra("TRADE_TYPE") ?: ""
                    val pnlRoe = intent.getDoubleExtra("TRADE_PNL", 0.0)
                    val pnlCash = intent.getDoubleExtra("TRADE_PNL_CASH", 0.0)
                    actualizarTarjetaVisual(true, symbol, type, pnlRoe, pnlCash)
                } else {
                    actualizarTarjetaVisual(false)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_bot, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vincularVistas(view)

        btnCloseTrade.setOnClickListener {
            val intent = Intent("FORZAR_CIERRE_MANUAL")
            requireContext().sendBroadcast(intent)
            btnCloseTrade.text = "CERRANDO..."
            btnCloseTrade.isEnabled = false
        }

        switchBotActive.isChecked = TradingService.isRunning
        switchBotActive.setOnCheckedChangeListener { _, isChecked ->
            val intent = Intent(requireContext(), TradingService::class.java)
            if (isChecked) {
                ContextCompat.startForegroundService(requireContext(), intent)
                actualizarUIEstado(true)
            } else {
                requireContext().stopService(intent)
                actualizarUIEstado(false)
            }
        }
    }

    private fun vincularVistas(view: View) {
        tvLivePrice = view.findViewById(R.id.tvLivePrice)
        tvTotalBalance = view.findViewById(R.id.tvTotalBalance)
        txtRSI = view.findViewById(R.id.txtRSI)
        txtVolatilidad = view.findViewById(R.id.txtVolatilidad)
        txtTendencia = view.findViewById(R.id.txtTendencia)
        switchBotActive = view.findViewById(R.id.switchBotActive)
        chipStatus = view.findViewById(R.id.chipStatus)

        cardActiveTrade = view.findViewById(R.id.cardActiveTrade)
        tvTradeSymbol = view.findViewById(R.id.tvTradeSymbol)
        tvTradeType = view.findViewById(R.id.tvTradeType)
        tvTradePnL = view.findViewById(R.id.tvTradePnL)
        btnCloseTrade = view.findViewById(R.id.btnCloseTrade)
    }

    private fun actualizarTarjetaVisual(activo: Boolean, sym: String = "", type: String = "", roe: Double = 0.0, cash: Double = 0.0) {
        cardActiveTrade.visibility = View.VISIBLE // SIEMPRE VISIBLE

        if (activo) {
            tvTradeSymbol.text = "POSICIÓN ACTIVA: $sym"
            tvTradeType.text = if (type == "LONG") "LONG ↗" else "SHORT ↘"
            tvTradeType.setTextColor(ContextCompat.getColor(requireContext(), if (type == "LONG") R.color.neon_green else R.color.neon_red))

            tvTradePnL.text = "%+.2f%% (%+.2f USDT)".format(roe, cash)
            tvTradePnL.setTextColor(ContextCompat.getColor(requireContext(), if (roe >= 0) R.color.neon_green else R.color.neon_red))

            btnCloseTrade.visibility = View.VISIBLE
            btnCloseTrade.isEnabled = true
            btnCloseTrade.text = "CERRAR POSICIÓN (PÁNICO)"
        } else {
            tvTradeSymbol.text = "SISTEMA DE ESCANEO"
            tvTradeType.text = "BUSCANDO SEÑALES..."
            tvTradeType.setTextColor(Color.GRAY)
            tvTradePnL.text = "Scanner activo en tiempo real"
            tvTradePnL.setTextColor(Color.GRAY)
            btnCloseTrade.visibility = View.GONE
        }
    }

    private fun colorearRSI(rsiStr: String) {
        val rsi = rsiStr.toDoubleOrNull() ?: 50.0
        val color = when {
            rsi < 40 -> R.color.neon_green
            rsi > 60 -> R.color.neon_red
            else -> R.color.neon_gold
        }
        txtRSI.setTextColor(ContextCompat.getColor(requireContext(), color))
    }

    private fun actualizarUIEstado(online: Boolean) {
        chipStatus.text = if (online) "● ONLINE" else "● OFFLINE"
        chipStatus.setTextColor(ContextCompat.getColor(requireContext(), if (online) R.color.neon_green else R.color.text_grey))
        if (!online) actualizarTarjetaVisual(false)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("ACTUALIZACION_TRADING")
        requireContext().registerReceiver(tradingReceiver, filter, Context.RECEIVER_EXPORTED)

        // 🔥 PASO MAESTRO: Sincronización Inmediata al volver a la App
        requireContext().sendBroadcast(Intent("SOLICITAR_REFRESH_UI"))

        // Leemos las variables estáticas directamente por si el broadcast tarda
        if (TradingService.isRunning) {
            actualizarUIEstado(true)
            tvTotalBalance.text = "$%.2f".format(TradingService.currentBalance)

            if (TradingService.posicionAbierta) {
                actualizarTarjetaVisual(
                    true,
                    TradingService.monedaConPosicion,
                    TradingService.tipoPosicion
                )
            } else {
                actualizarTarjetaVisual(false)
            }
        } else {
            actualizarUIEstado(false)
        }
    }

    override fun onPause() {
        super.onPause()
        try { requireContext().unregisterReceiver(tradingReceiver) } catch (e: Exception) {}
    }
}