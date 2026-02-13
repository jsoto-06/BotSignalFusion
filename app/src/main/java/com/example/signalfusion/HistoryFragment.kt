package com.example.signalfusion

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.*

class HistoryFragment : Fragment() {

    private lateinit var chartBalance: LineChart
    private lateinit var tvHistoryBalance: TextView
    private lateinit var rvHistory: RecyclerView // 🆕 RecyclerView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chartBalance = view.findViewById(R.id.chartBalance)
        tvHistoryBalance = view.findViewById(R.id.tvHistoryBalance)
        rvHistory = view.findViewById(R.id.rvHistory)

        // Configurar RecyclerView
        rvHistory.layoutManager = LinearLayoutManager(context)

        configurarGrafico()
    }

    override fun onResume() {
        super.onResume()
        cargarDatos()
    }

    private fun configurarGrafico() {
        chartBalance.description.isEnabled = false
        chartBalance.setTouchEnabled(true)
        chartBalance.isDragEnabled = true
        chartBalance.setScaleEnabled(true)
        chartBalance.setDrawGridBackground(false)
        chartBalance.legend.isEnabled = false
        chartBalance.animateX(1000) // Animación suave al entrar

        val xAxis = chartBalance.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.textColor = Color.LTGRAY
        xAxis.valueFormatter = object : ValueFormatter() {
            private val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            override fun getFormattedValue(value: Float): String {
                return sdf.format(Date(value.toLong()))
            }
        }

        val yAxisLeft = chartBalance.axisLeft
        yAxisLeft.textColor = Color.WHITE
        yAxisLeft.setDrawGridLines(true)
        yAxisLeft.gridColor = Color.parseColor("#33FFFFFF")
        chartBalance.axisRight.isEnabled = false
    }

    private fun cargarDatos() {
        val prefs = requireContext().getSharedPreferences("BotHistory", Context.MODE_PRIVATE)
        val rawData = prefs.getString("GRAPH_DATA", "") ?: ""

        // 1. Mostrar Balance
        val currentBal = TradingService.currentBalance
        tvHistoryBalance.text = "$%.2f".format(currentBal)

        // 2. Pintar Gráfico
        val entries = ArrayList<Entry>()
        if (rawData.isEmpty()) entries.add(Entry(System.currentTimeMillis().toFloat(), 1000f))

        if (rawData.isNotEmpty()) {
            val puntos = rawData.split(";")
            puntos.forEach { punto ->
                if (punto.contains(":")) {
                    val partes = punto.split(":")
                    val tiempo = partes[0].toFloatOrNull() ?: 0f
                    val dinero = partes[1].toDoubleOrNull()?.toFloat() ?: 0f
                    if (tiempo > 0) entries.add(Entry(tiempo, dinero))
                }
            }
        }
        // Conectar con el presente
        entries.add(Entry(System.currentTimeMillis().toFloat(), currentBal.toFloat()))

        val dataSet = LineDataSet(entries, "Balance")
        dataSet.color = ContextCompat.getColor(requireContext(), R.color.neon_green)
        dataSet.lineWidth = 2f
        dataSet.setDrawCircles(false)
        dataSet.setDrawValues(false)
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER
        dataSet.setDrawFilled(true)
        dataSet.fillColor = ContextCompat.getColor(requireContext(), R.color.neon_green)
        dataSet.fillAlpha = 50

        chartBalance.data = LineData(dataSet)
        chartBalance.invalidate()

        // 3. 🆕 Cargar Lista Profesional
        val rawLogs = TradingService.logHistory.toString()
        // Filtramos solo las líneas que sean CIERRES (CLOSE) para que quede limpio
        val listaOperaciones = rawLogs.split("\n")
            .filter { it.contains("CLOSE") } // Solo mostramos los resultados finales

        val adapter = HistoryAdapter(listaOperaciones)
        rvHistory.adapter = adapter
    }
}