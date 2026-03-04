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
import androidx.lifecycle.lifecycleScope
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvWinRate    = view.findViewById(R.id.tvWinRate) ?: TextView(context)
        tvTotalPnL   = view.findViewById(R.id.tvHistoryBalance)
        tvTradeCount = view.findViewById(R.id.tvTotalTrades) ?: TextView(context)

        rvHistory = view.findViewById(R.id.rvHistory)
        rvHistory.layoutManager = LinearLayoutManager(context)

        fetchBitgetHistory()
    }

    private fun fetchBitgetHistory() {
        // ✅ FIX: Usar viewLifecycleOwner.lifecycleScope evita crash si el fragment
        // se destruye mientras la coroutine sigue corriendo (requireContext() seguro)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("API_KEY", "") ?: ""
                val secret  = prefs.getString("SECRET_KEY", "") ?: ""
                val pass    = prefs.getString("API_PASSPHRASE", "") ?: ""

                // ✅ FIX CRÍTICO: Usa BitgetConfig en lugar de hardcodear USDT-FUTURES
                // En modo DEMO devuelve SUSDT-FUTURES, en REAL devuelve USDT-FUTURES
                BitgetConfig.loadFromPreferences(requireContext())
                val productType = BitgetConfig.getProductType()

                Log.d("HistoryFragment", "🔍 Descargando historial | Modo: ${if (BitgetConfig.isDemo()) "DEMO" else "REAL"} | productType: $productType")

                val endpoint = "/api/v2/mix/order/orders-history?productType=${productType}&limit=100"
                val resp = BitgetUtils.authenticatedGet(endpoint, apiKey, secret, pass)

                if (resp != null) {
                    // Log la respuesta COMPLETA para debug
                    Log.d("HistoryFragment", "📦 Respuesta RAW (primeros 400): ${resp.take(400)}")

                    val json = JSONObject(resp)
                    val code = json.optString("code")
                    Log.d("HistoryFragment", "🔍 Code: $code | msg: ${json.optString("msg")}")

                    if (code == "00000") {
                        // Bitget V2 devuelve data como JSONObject {orderList:[], endId:""} O como JSONArray directo
                        val rawData = json.opt("data")
                        Log.d("HistoryFragment", "📊 data type: ${rawData?.javaClass?.simpleName}")

                        val dataArray = when {
                            rawData is org.json.JSONArray  -> rawData
                            rawData is org.json.JSONObject -> {
                                Log.d("HistoryFragment", "📊 JSONObject keys: ${rawData.keys().asSequence().toList()}")
                                rawData.optJSONArray("orderList")
                                    ?: rawData.optJSONArray("orders")
                                    ?: rawData.optJSONArray("list")
                            }
                            else -> null
                        }
                        Log.d("HistoryFragment", "📊 dataArray size: ${dataArray?.length() ?: "NULL"}")
                        tradeList.clear()

                        if (dataArray != null && dataArray.length() > 0) {
                            Log.d("HistoryFragment", "✅ ${dataArray.length()} registros en orderList")

                            var wins = 0
                            var losses = 0
                            var totalPnL = 0.0

                            for (i in 0 until dataArray.length()) {
                                val order = dataArray.getJSONObject(i)

                                // Parsear PnL de múltiples campos posibles según versión de API
                                var pnl = order.optDouble("profit", 0.0)
                                if (pnl == 0.0) pnl = order.optDouble("realizedPL", 0.0)
                                if (pnl == 0.0) pnl = order.optDouble("netProfit", 0.0)

                                // Solo mostrar órdenes con PnL (son las operaciones cerradas)
                                if (pnl != 0.0) {
                                    val symbol = order.optString("symbol", "UNK")

                                    var side = order.optString("posSide", "").lowercase()
                                    if (side.isEmpty()) side = order.optString("side", "").lowercase()
                                    val sideDisplay = if (side.contains("long") || side.contains("buy")) "LONG" else "SHORT"

                                    val time = order.optLong(
                                        "uTime",
                                        order.optLong("cTime", System.currentTimeMillis())
                                    )

                                    tradeList.add(TradeData(symbol, sideDisplay, pnl, time))
                                    totalPnL += pnl
                                    if (pnl > 0) wins++ else losses++

                                    Log.d("HistoryFragment", "💼 $symbol $sideDisplay PnL=${"%.2f".format(pnl)}")
                                }
                            }

                            val totalTrades = wins + losses
                            val winRate = if (totalTrades > 0) (wins.toDouble() / totalTrades) * 100 else 0.0

                            Log.d("HistoryFragment", "📊 $totalTrades trades | WR: ${"%.1f".format(winRate)}% | PnL: ${"%.2f".format(totalPnL)}")

                            withContext(Dispatchers.Main) {
                                rvHistory.adapter = HistoryAdapter(tradeList)

                                val color = if (totalPnL >= 0) "#00E676" else "#FF5252"
                                tvTotalPnL.text = "PnL Total: $${"%.2f".format(totalPnL)} USDT"
                                tvTotalPnL.setTextColor(Color.parseColor(color))

                                tvWinRate.text = "Win Rate: ${"%.1f".format(winRate)}% ($wins/$totalTrades)"
                                tvWinRate.setTextColor(
                                    Color.parseColor(if (winRate >= 50) "#00E676" else "#FF5252")
                                )

                                tvTradeCount.text = "$totalTrades Trades"
                            }
                        } else {
                            Log.d("HistoryFragment", "📜 Sin datos en el array")
                            withContext(Dispatchers.Main) {
                                tvTotalPnL.text = "Sin historial"
                                tvWinRate.text = "0.0%"
                                tvTradeCount.text = "0 Trades"
                            }
                        }
                    } else {
                        Log.e("HistoryFragment", "❌ Error API code=$code msg=${json.optString("msg")}")
                        Log.e("HistoryFragment", "❌ Respuesta completa: ${resp.take(500)}")
                        withContext(Dispatchers.Main) {
                            tvTotalPnL.text = "Error al cargar"
                            tvWinRate.text = "--"
                            tvTradeCount.text = "--"
                        }
                    }
                } else {
                    Log.e("HistoryFragment", "❌ Respuesta nula de la API")
                }
            } catch (e: Exception) {
                Log.e("HistoryFragment", "💥 Exception: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}