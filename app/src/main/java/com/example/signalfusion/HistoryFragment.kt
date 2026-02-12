package com.example.signalfusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class HistoryFragment : Fragment() {

    private lateinit var tvLogHistory: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnClear: ImageView

    // Receptor para actualizar el log en tiempo real
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTUALIZACION_TRADING") {
                val logMsg = intent.getStringExtra("LOG_MSG")
                if (logMsg != null) {
                    appendLog(logMsg)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvLogHistory = view.findViewById(R.id.tvLogHistory)
        scrollView = view.findViewById(R.id.scrollViewHistory)
        btnClear = view.findViewById(R.id.btnClearLog)

        // Cargar historial acumulado en memoria
        tvLogHistory.text = TradingService.logHistory.toString()

        // Botón Borrar
        btnClear.setOnClickListener {
            TradingService.logHistory.clear()
            tvLogHistory.text = "> Log limpiado.\n> Esperando nuevos eventos..."
        }
    }

    private fun appendLog(msg: String) {
        // Añadir texto al principio o final (aquí lo ponemos al principio para ver lo nuevo arriba)
        val currentText = tvLogHistory.text.toString()
        tvLogHistory.text = "$msg$currentText"
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("ACTUALIZACION_TRADING")
        ContextCompat.registerReceiver(requireContext(), logReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        // Refrescar al entrar
        tvLogHistory.text = TradingService.logHistory.toString()
    }

    override fun onPause() {
        super.onPause()
        try { requireContext().unregisterReceiver(logReceiver) } catch (e: Exception) {}
    }
}