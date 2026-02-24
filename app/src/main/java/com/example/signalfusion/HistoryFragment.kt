package com.example.signalfusion

import android.content.Context
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

class HistoryFragment : Fragment() {

    private lateinit var tvHistoryBalance: TextView
    private lateinit var rvHistory: RecyclerView
    private val tradeList = mutableListOf<TradeData>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Asegúrate de que el layout se llame fragment_history
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Inicializar vistas (Faltaba esto en tu código)
        tvHistoryBalance = view.findViewById(R.id.tvHistoryBalance)
        rvHistory = view.findViewById(R.id.rvHistory) // Asegúrate de tener este ID en el XML

        rvHistory.layoutManager = LinearLayoutManager(context)

        // Cargar historial
        fetchBitgetHistory()
    }

    private fun fetchBitgetHistory() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("API_KEY", "") ?: ""
                val secret = prefs.getString("SECRET_KEY", "") ?: ""
                val pass = prefs.getString("API_PASSPHRASE", "") ?: ""

                // Endpoint de historial de órdenes (últimos 7 días)
                val endpoint = "/api/v2/mix/order/history-orders?productType=USDT-FUTURES&limit=50"

                val resp = BitgetUtils.authenticatedGet(endpoint, apiKey, secret, pass)

                if (resp != null) {
                    val json = JSONObject(resp)
                    if (json.optString("code") == "00000") {
                        val dataArray = json.optJSONArray("data")

                        tradeList.clear()

                        if (dataArray != null) {
                            var totalPnL = 0.0

                            for (i in 0 until dataArray.length()) {
                                val item = dataArray.getJSONObject(i)

                                // Extraer datos reales del JSON de Bitget
                                val sym = item.optString("symbol", "UNK")
                                val side = item.optString("side", "buy") // buy/sell
                                // En historial, buscamos el profit realizado
                                val pnl = item.optString("totalProfits", "0.0").toDoubleOrNull() ?: 0.0
                                val time = item.optString("uTime", "0").toLongOrNull() ?: 0L

                                // Solo agregamos si tiene PnL (es una orden cerrada/trade)
                                if (pnl != 0.0) {
                                    tradeList.add(TradeData(sym, side, pnl, time))
                                    totalPnL += pnl
                                }
                            }

                            // 🚨 CRÍTICO: Actualizar la UI en el Hilo Principal
                            withContext(Dispatchers.Main) {
                                rvHistory.adapter = HistoryAdapter(tradeList)

                                // Actualizar balance visual del historial
                                val color = if (totalPnL >= 0) "#00E676" else "#FF5252"
                                tvHistoryBalance.text = "PnL Reciente: ${"%.2f".format(totalPnL)} USDT"
                                tvHistoryBalance.setTextColor(android.graphics.Color.parseColor(color))
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