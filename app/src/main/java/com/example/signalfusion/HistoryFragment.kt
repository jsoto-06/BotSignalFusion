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
        // Usamos tu layout existente
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvHistoryBalance = view.findViewById(R.id.tvHistoryBalance)
        rvHistory = view.findViewById(R.id.rvHistory) // Asegúrate de que en el XML se llame rvHistory

        rvHistory.layoutManager = LinearLayoutManager(context)

        // Cargar saldo rápido desde caché
        val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        val lastBalance = prefs.getString("LAST_KNOWN_BALANCE", "0.00")
        tvHistoryBalance.text = "$$lastBalance"

        // 🔥 DESCAGAR DATOS REALES DE BITGET
        fetchBitgetHistory()
    }

    private fun fetchBitgetHistory() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("API_KEY", "") ?: ""
                val secret = prefs.getString("SECRET_KEY", "") ?: ""
                val pass = prefs.getString("API_PASSPHRASE", "") ?: ""

                // Llamada a la API para ver historial de últimos 30 días
                // Endpoint: /api/v2/mix/position/history-position
                val endpoint = "/api/v2/mix/position/history-position?productType=USDT-FUTURES&limit=50"

                val resp = BitgetUtils.authenticatedGet(endpoint, apiKey, secret, pass)

                if (resp != null) {
                    val json = JSONObject(resp)
                    if (json.optString("code") == "00000") {
                        val data = json.getJSONObject("data").getJSONArray("list")
                        tradeList.clear()

                        for (i in 0 until data.length()) {
                            val item = data.getJSONObject(i)

                            // Extraer datos
                            val sym = item.getString("symbol")
                            val side = item.getString("holdSide").uppercase() // LONG o SHORT
                            val pnl = item.getString("achievedProfits").toDoubleOrNull() ?: 0.0
                            val time = item.getString("cTime").toLongOrNull() ?: System.currentTimeMillis()

                            tradeList.add(TradeData(sym, side, pnl, time))
                        }

                        // Actualizar UI
                        withContext(Dispatchers.Main) {
                            rvHistory.adapter = HistoryAdapter(tradeList)
                            if (tradeList.isNotEmpty()) {
                                // Actualizar saldo visual sumando el último
                                // (Opcional, solo visual)
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