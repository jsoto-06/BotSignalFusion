package com.example.signalfusion

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import org.json.JSONObject

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

        tvWinRate = view.findViewById(R.id.tvWinRate) ?: TextView(context)
        tvTotalPnL = view.findViewById(R.id.tvHistoryBalance)
        tvTradeCount = view.findViewById(R.id.tvTotalTrades) ?: TextView(context)

        rvHistory = view.findViewById(R.id.rvHistory)
        rvHistory.layoutManager = LinearLayoutManager(context)

        fetchBitgetHistory()
    }

    private fun fetchBitgetHistory() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("API_KEY", "") ?: ""
                val secret = prefs.getString("SECRET_KEY", "") ?: ""
                val pass = prefs.getString("API_PASSPHRASE", "") ?: ""

                Log.d("HistoryFragment", "🔍 Iniciando descarga historial...")

                // ✅ MISMO ENDPOINT QUE TradingService
                val endpoint = "/api/v2/mix/order/history-orders?productType=USDT-FUTURES&limit=100"

                val resp = BitgetUtils.authenticatedGet(endpoint, apiKey, secret, pass)

                if (resp != null) {
                    Log.d("HistoryFragment", "📦 Respuesta recibida: ${resp.take(200)}...")

                    val json = JSONObject(resp)
                    val code = json.optString("code")
                    Log.d("HistoryFragment", "🔍 Code: $code")

                    if (code == "00000") {
                        val dataArray = json.optJSONArray("data")

                        tradeList.clear()

                        if (dataArray != null && dataArray.length() > 0) {
                            Log.d("HistoryFragment", "✅ Encontrados ${dataArray.length()} registros")

                            var wins = 0
                            var losses = 0
                            var totalPnL = 0.0

                            for (i in 0 until dataArray.length()) {
                                val order = dataArray.getJSONObject(i)

                                // Parsear PnL de múltiples campos posibles
                                var pnl = order.optDouble("profit", 0.0)
                                if (pnl == 0.0) pnl = order.optDouble("realizedPL", 0.0)
                                if (pnl == 0.0) pnl = order.optDouble("netProfit", 0.0)

                                // Solo mostrar órdenes con PnL (closes)
                                if (pnl != 0.0) {
                                    val symbol = order.optString("symbol", "UNK")

                                    // Detectar LONG/SHORT
                                    var side = order.optString("posSide", "").lowercase()
                                    if (side.isEmpty()) side = order.optString("side", "").lowercase()
                                    val sideDisplay = if (side.contains("long") || side.contains("buy")) "LONG" else "SHORT"

                                    val time = order.optLong("uTime", order.optLong("cTime", System.currentTimeMillis()))

                                    tradeList.add(TradeData(symbol, sideDisplay, pnl, time))

                                    totalPnL += pnl
                                    if (pnl > 0) wins++ else losses++

                                    Log.d("HistoryFragment", "💼 Trade: $symbol $sideDisplay PnL=${"%.2f".format(pnl)}")
                                }
                            }

                            val totalTrades = wins + losses
                            val winRate = if (totalTrades > 0) (wins.toDouble() / totalTrades.toDouble()) * 100 else 0.0

                            Log.d("HistoryFragment", "📊 Total trades: $totalTrades ($wins W / $losses L)")
                            Log.d("HistoryFragment", "📊 Win Rate: ${"%.1f".format(winRate)}%")
                            Log.d("HistoryFragment", "💰 PnL Total: $${"%.2f".format(totalPnL)}")

                            withContext(Dispatchers.Main) {
                                rvHistory.adapter = HistoryAdapter(tradeList)

                                val color = if (totalPnL >= 0) "#00E676" else "#FF5252"

                                tvTotalPnL.text = "PnL Total: $${"%.2f".format(totalPnL)} USDT"
                                tvTotalPnL.setTextColor(Color.parseColor(color))

                                tvWinRate.text = "Win Rate: ${"%.1f".format(winRate)}% ($wins/$totalTrades)"
                                if (winRate >= 50) tvWinRate.setTextColor(Color.parseColor("#00E676"))
                                else tvWinRate.setTextColor(Color.parseColor("#FF5252"))

                                tvTradeCount.text = "$totalTrades Trades"
                            }
                        } else {
                            Log.d("HistoryFragment", "📜 No hay datos en el array")
                            withContext(Dispatchers.Main) {
                                tvTotalPnL.text = "Sin historial"
                                tvWinRate.text = "0.0%"
                                tvTradeCount.text = "0 Trades"
                            }
                        }
                    } else {
                        Log.e("HistoryFragment", "❌ Error code: $code - ${json.optString("msg")}")
                    }
                } else {
                    Log.e("HistoryFragment", "❌ Respuesta nula")
                }
            } catch (e: Exception) {
                Log.e("HistoryFragment", "💥 Exception: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}