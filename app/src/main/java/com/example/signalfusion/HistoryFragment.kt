package com.example.signalfusion

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
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
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Inicializar Vistas
        tvHistoryBalance = view.findViewById(R.id.tvHistoryBalance)
        rvHistory = view.findViewById(R.id.rvHistory)
        val btnRefresh = view.findViewById<ImageButton>(R.id.btnRefreshHistory)

        rvHistory.layoutManager = LinearLayoutManager(context)

        // 2. Cargar saldo rápido desde caché (Evitando el error del doble $$)
        val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        val lastBalanceStr = prefs.getString("LAST_KNOWN_BALANCE", "0.00") ?: "0.00"
        val lastBalanceNum = lastBalanceStr.toDoubleOrNull() ?: 0.0
        tvHistoryBalance.text = "$${"%.2f".format(lastBalanceNum)}"

        // 3. Configurar el Botón de Refrescar manual 🔄
        btnRefresh.setOnClickListener {
            // Animación de rotación para que sepa que está trabajando
            it.animate().rotationBy(360f).setDuration(600).start()

            // Lanzamos la descarga de datos reales de Bitget
            fetchBitgetHistory()

            Toast.makeText(context, "Sincronizando con Bitget...", Toast.LENGTH_SHORT).show()
        }

        // 4. Carga inicial automática
        fetchBitgetHistory()
    }

    private fun fetchBitgetHistory() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("API_KEY", "") ?: ""
                val secret = prefs.getString("SECRET_KEY", "") ?: ""
                val pass = prefs.getString("API_PASSPHRASE", "") ?: ""

                if (apiKey.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Falta API Key", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 🔧 BITGET API V2 REQUIERE TIMESTAMPS OBLIGATORIOS (Últimos 7 días)
                val endTime = System.currentTimeMillis()
                val startTime = endTime - (7L * 24 * 60 * 60 * 1000)

                val endpoint = "/api/v2/mix/position/history-position?productType=USDT-FUTURES&limit=50&startTime=$startTime&endTime=$endTime"

                val resp = BitgetUtils.authenticatedGet(endpoint, apiKey, secret, pass)

                withContext(Dispatchers.Main) {
                    if (resp != null) {
                        val json = JSONObject(resp)
                        if (json.optString("code") == "00000") {

                            // 🔧 PARSEO SEGURO: Evita crasheos si Bitget cambia la estructura del JSON
                            val dataObj = json.optJSONObject("data")
                            val listArray = dataObj?.optJSONArray("list") ?: json.optJSONArray("data")

                            tradeList.clear()

                            if (listArray != null && listArray.length() > 0) {
                                for (i in 0 until listArray.length()) {
                                    val item = listArray.getJSONObject(i)

                                    val sym = item.optString("symbol", "UNKNOWN")
                                    val side = item.optString("holdSide", "LONG").uppercase()

                                    // 🔧 Busca el PnL en varios nombres posibles
                                    val pnlStr = item.optString("netProfit", item.optString("achievedProfits", item.optString("pnl", "0.0")))
                                    val pnl = pnlStr.toDoubleOrNull() ?: 0.0

                                    // 🔧 Usa el tiempo de actualización real
                                    val time = item.optString("uTime", item.optString("cTime", "${System.currentTimeMillis()}")).toLongOrNull() ?: System.currentTimeMillis()

                                    tradeList.add(TradeData(sym, side, pnl, time))
                                }

                                // Pintar la lista en pantalla
                                rvHistory.adapter = HistoryAdapter(tradeList)

                            } else {
                                Toast.makeText(requireContext(), "No hay operaciones cerradas en los últimos 7 días", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(requireContext(), "Error API: ${json.optString("msg")}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(requireContext(), "Error de conexión con Bitget", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error procesando historial: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}