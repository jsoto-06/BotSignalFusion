package com.example.signalfusion

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class HistoryAdapter(private val trades: List<TradeData>) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon: TextView = view.findViewById(R.id.tvIcon)
        val tvSymbol: TextView = view.findViewById(R.id.tvSymbol)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvPnlAmount: TextView = view.findViewById(R.id.tvPnlAmount)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_trade, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val t = trades[position]

        // 1. Datos básicos
        holder.tvSymbol.text = "${t.symbol} (${t.side})"
        holder.tvTime.text = convertirHora(t.time)

        // 2. Colores según PnL
        val esProfit = t.pnl >= 0
        val color = if (esProfit) Color.parseColor("#00E676") else Color.parseColor("#FF5252") // Verde o Rojo

        holder.tvPnlAmount.text = "${if(esProfit) "+" else ""}${"%.2f".format(t.pnl)} USDT"
        holder.tvPnlAmount.setTextColor(color)

        holder.tvStatus.text = if (esProfit) "PROFIT" else "LOSS"
        holder.tvStatus.setTextColor(color)

        holder.tvIcon.text = if (esProfit) "💰" else "📉"
    }

    override fun getItemCount() = trades.size

    private fun convertirHora(ms: Long): String {
        return SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ms))
    }
}