package com.example.signalfusion

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryAdapter(private val trades: List<String>) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon: TextView = view.findViewById(R.id.tvIcon)
        val tvAction: TextView = view.findViewById(R.id.tvAction)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        val tvPnlPercent: TextView = view.findViewById(R.id.tvPnlPercent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trade, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // Formato esperado: "[14:30:00] CLOSE | ✅ Take Profit | PnL: 2.15% ($4.29)"
        val linea = trades[position]

        try {
            // Parseo inteligente de texto a datos visuales
            val hora = linea.substringAfter("[").substringBefore("]")

            // Detectar si es Ganancia o Pérdida
            val esWin = linea.contains("Profit") || linea.contains("Trailing")
            val color = if (esWin) Color.parseColor("#00FF9D") else Color.parseColor("#FF2A6D")

            holder.tvTime.text = hora
            holder.tvAction.text = if (esWin) "PROFIT / TRAILING" else "STOP LOSS / CIERRE"
            holder.tvIcon.text = if (esWin) "💰" else "🛑"

            // Extraer dinero y porcentaje
            val pnlPart = linea.substringAfter("PnL:").trim()
            val porcentaje = pnlPart.substringBefore("%") + "%"
            val dinero = pnlPart.substringAfter("(").substringBefore(")")

            holder.tvAmount.text = dinero
            holder.tvPnlPercent.text = porcentaje

            // Colores
            holder.tvAmount.setTextColor(color)
            holder.tvPnlPercent.setTextColor(color)
            holder.tvIcon.setBackgroundColor(if (esWin) Color.parseColor("#2200FF9D") else Color.parseColor("#22FF2A6D"))

        } catch (e: Exception) {
            // Si la línea no tiene el formato estándar, mostrar genérico
            holder.tvAction.text = "Registro de Sistema"
            holder.tvTime.text = linea
            holder.tvAmount.text = "--"
            holder.tvPnlPercent.text = ""
        }
    }

    override fun getItemCount() = trades.size
}