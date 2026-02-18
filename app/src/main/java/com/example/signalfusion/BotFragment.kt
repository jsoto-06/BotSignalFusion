package com.example.signalfusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
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
    private lateinit var txtTendencia: TextView
    private lateinit var txtEstadoDetalle: TextView
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
                val statusMsg = intent.getStringExtra("STATUS_MSG")
                val tradeOpen = intent.getBooleanExtra("IS_TRADE_OPEN", false)

                if (statusMsg != null) txtEstadoDetalle.text = statusMsg
                tvLivePrice.text = "$${TradingService.lastPrice}"
                tvTotalBalance.text = "$%.2f".format(TradingService.currentBalance)
                if (TradingService.lastRSI != "--") txtRSI.text = TradingService.lastRSI

                if (tradeOpen) actualizarTarjetaConOperacion(intent)
                else actualizarTarjetaModoEscaneo()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_bot, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvLivePrice = view.findViewById(R.id.tvLivePrice)
        tvTotalBalance = view.findViewById(R.id.tvTotalBalance)
        txtRSI = view.findViewById(R.id.txtRSI)
        txtTendencia = view.findViewById(R.id.txtTendencia)
        txtEstadoDetalle = view.findViewById(R.id.txtEstadoDetalle)
        switchBotActive = view.findViewById(R.id.switchBotActive)
        chipStatus = view.findViewById(R.id.chipStatus)

        cardActiveTrade = view.findViewById(R.id.cardActiveTrade)
        tvTradeSymbol = view.findViewById(R.id.tvTradeSymbol)
        tvTradeType = view.findViewById(R.id.tvTradeType)
        tvTradePnL = view.findViewById(R.id.tvTradePnL)
        btnCloseTrade = view.findViewById(R.id.btnCloseTrade)

        // 1. CARGAR SALDO MEMORIA
        val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        val savedBalance = prefs.getString("LAST_KNOWN_BALANCE", "0.00")?.toDoubleOrNull() ?: 0.00
        tvTotalBalance.text = "$%.2f".format(savedBalance)

        // 2. SINCRONIZAR ESTADO DEL BOTÓN AL ABRIR
        if (TradingService.isRunning) {
            switchBotActive.isChecked = true
            actualizarEstadoVisual(true)
        } else {
            switchBotActive.isChecked = false
            actualizarEstadoVisual(false)
        }

        // 3. ESTADO INICIAL DE TARJETA
        cardActiveTrade.visibility = View.VISIBLE
        if (TradingService.posicionAbierta) {
            // Si cerramos y abrimos la app con operación en curso, restaurar visual
            tvTradeSymbol.text = "POSICIÓN ACTIVA: ${TradingService.monedaConPosicion}"
            tvTradeType.text = TradingService.tipoPosicion
            btnCloseTrade.visibility = View.VISIBLE
        } else {
            actualizarTarjetaModoEscaneo()
        }

        switchBotActive.setOnCheckedChangeListener { _, isChecked ->
            actualizarEstadoVisual(isChecked)
            if (isChecked) {
                if (!TradingService.isRunning) {
                    ContextCompat.startForegroundService(
                        requireContext(), Intent(requireContext(), TradingService::class.java)
                    )
                }
            } else {
                requireContext().stopService(Intent(requireContext(), TradingService::class.java))
                actualizarTarjetaModoEscaneo()
            }
        }

        btnCloseTrade.setOnClickListener {
            val intent = Intent("FORZAR_CIERRE_MANUAL")
            requireContext().sendBroadcast(intent)
        }

        ContextCompat.registerReceiver(
            requireContext(),
            tradingReceiver,
            IntentFilter("ACTUALIZACION_TRADING"),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun actualizarEstadoVisual(online: Boolean) {
        if (online) {
            chipStatus.text = "● ONLINE"
            chipStatus.setChipBackgroundColorResource(R.color.neon_green)
        } else {
            chipStatus.text = "● OFFLINE"
            chipStatus.setChipBackgroundColorResource(R.color.text_secondary)
        }
    }

    private fun actualizarTarjetaConOperacion(intent: Intent?) {
        val symbol = intent?.getStringExtra("TRADE_SYMBOL") ?: "UNK"
        val type = intent?.getStringExtra("TRADE_TYPE") ?: "-"
        val pnl = intent?.getDoubleExtra("TRADE_PNL", 0.0) ?: 0.0

        val colorBorde = if (pnl >= 0) R.color.neon_green else R.color.neon_red
        cardActiveTrade.strokeColor = ContextCompat.getColor(requireContext(), colorBorde)
        cardActiveTrade.strokeWidth = 4

        tvTradeSymbol.text = "POSICIÓN ACTIVA: $symbol"
        tvTradeSymbol.setTextColor(Color.WHITE)

        tvTradeType.text = type
        val typeColor = if (type == "LONG") R.color.neon_green else R.color.neon_red
        tvTradeType.setTextColor(ContextCompat.getColor(requireContext(), typeColor))

        val pnlText = "${if (pnl > 0) "+" else ""}%.2f%%".format(pnl)
        tvTradePnL.text = pnlText
        val pnlColor = if (pnl >= 0) R.color.neon_green else R.color.neon_red
        tvTradePnL.setTextColor(ContextCompat.getColor(requireContext(), pnlColor))

        btnCloseTrade.visibility = View.VISIBLE
        btnCloseTrade.text = "CERRAR POSICIÓN"

        txtTendencia.text = if (type == "LONG") "ALCISTA ↗" else "BAJISTA ↘"
    }

    private fun actualizarTarjetaModoEscaneo() {
        cardActiveTrade.strokeColor = Color.DKGRAY
        cardActiveTrade.strokeWidth = 2

        tvTradeSymbol.text = "🔍 ESCANEANDO MERCADO..."
        tvTradeSymbol.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

        tvTradeType.text = "BUSCANDO SEÑAL"
        tvTradeType.setTextColor(Color.GRAY)

        tvTradePnL.text = "--"
        tvTradePnL.setTextColor(Color.GRAY)

        btnCloseTrade.visibility = View.INVISIBLE
        txtTendencia.text = "ANALIZANDO..."
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            requireContext().unregisterReceiver(tradingReceiver)
        } catch (e: Exception) {}
    }
}