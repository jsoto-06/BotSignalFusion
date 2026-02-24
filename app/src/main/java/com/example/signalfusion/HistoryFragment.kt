package com.example.signalfusion

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

class HistoryFragment : Fragment() {

    private lateinit var tvWinRate: TextView
    private lateinit var tvTotalPnL: TextView
    private lateinit var tvTradeCount: TextView
    private lateinit var rvHistory: RecyclerView
    private val tradeList = mutableListOf<TradeData>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Vincular vistas (Asegúrate de que estos IDs existan en tu XML)
        tvWinRate = view.findViewById(R.id.tvWinRate) ?: TextView(context) // Fallback porsi no existe
        tvTotalPnL = view.findViewById(R.id.tvHistoryBalance) // Usamos el del balance para el PnL Total
        tvTradeCount = view.findViewById(R.id.tvTotalTrades) ?: TextView(context)

        rvHistory = view.findViewById(R.id.rvHistory)
        rvHistory.layoutManager = LinearLayoutManager(context)

        // 2. Cargar datos
        fetchBitgetHistory()
    }

    private fun fetchBitgetHistory() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("API_KEY", "") ?: ""
                val secret = prefs.getString("SECRET_KEY", "") ?: ""
                val pass = prefs.getString("API_PASSPHRASE", "") ?: ""

                // Pedimos las últimas 100 órdenes de historial
                val endpoint = "/api/v2/mix/order/history-orders?productType=USDT-FUTURES&limit=100"

                val resp = BitgetUtils.authenticatedGet(endpoint, apiKey, secret, pass)

                if (resp != null) {
                    val json = JSONObject(resp)
                    if (json.optString("code") == "00000") {
                        val dataArray = json.optJSONArray("data")

                        tradeList.clear()

                        if (dataArray != null) {
                            var wins = 0
                            var losses = 0
                            var totalPnL = 0.0

                            for (i in 0 until dataArray.length()) {
                                val item = dataArray.getJSONObject(i)

                                // 1. Extraer PnL (Bitget a veces usa 'profit' o 'realizedPL')
                                var pnl = item.optDouble("profit", 0.0)
                                if (pnl == 0.0) pnl = item.optDouble("realizedPL", 0.0)

                                // Solo nos interesan órdenes que cerraron posición (tienen PnL)
                                if (pnl != 0.0) {
                                    val sym = item.optString("symbol", "UNK")

                                    // 2. Detectar si fue LONG o SHORT
                                    // La API v2 suele mandar "posSide": "long" o "short". Si no, miramos "side".
                                    var sideRaw = item.optString("posSide", "").lowercase()
                                    if (sideRaw.isEmpty()) sideRaw = item.optString("side", "").lowercase()

                                    val side = if (sideRaw.contains("long") || sideRaw.contains("buy")) "LONG" else "SHORT"

                                    val time = item.optLong("uTime", item.optLong("cTime", System.currentTimeMillis()))

                                    tradeList.add(TradeData(sym, side, pnl, time))

                                    // Estadísticas
                                    totalPnL += pnl
                                    if (pnl > 0) wins++ else losses++
                                }
                            }

                            // 3. Calcular Win Rate
                            val totalTrades = wins + losses
                            val winRate = if (totalTrades > 0) (wins.toDouble() / totalTrades.toDouble()) * 100 else 0.0

                            // 4. Actualizar UI en Hilo Principal
                            withContext(Dispatchers.Main) {
                                // Llenar lista
                                rvHistory.adapter = HistoryAdapter(tradeList)

                                // Llenar Cabecera de Estadísticas
                                val color = if (totalPnL >= 0) "#00E676" else "#FF5252"

                                tvTotalPnL.text = "PnL Total: ${"%.2f".format(totalPnL)} USDT"
                                tvTotalPnL.setTextColor(Color.parseColor(color))

                                tvWinRate.text = "Win Rate: ${"%.1f".format(winRate)}% ($wins/$totalTrades)"
                                if (winRate >= 50) tvWinRate.setTextColor(Color.parseColor("#00E676"))
                                else tvWinRate.setTextColor(Color.parseColor("#FF5252"))

                                // Si tienes un TextView para contar trades:
                                tvTradeCount.text = "$totalTrades Trades"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}