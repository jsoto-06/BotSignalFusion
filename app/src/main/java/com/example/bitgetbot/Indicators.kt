package com.example.bitgetbot

object Indicators {

    // Calcular RSI (Relative Strength Index)
    // Necesitamos una lista de precios de cierre ordenados del más viejo al más nuevo
    fun calculateRSI(closingPrices: List<Double>, period: Int = 14): Double {
        if (closingPrices.size < period + 1) return 50.0 // No hay datos suficientes

        var gains = 0.0
        var losses = 0.0

        // 1. Calcular el primer promedio
        for (i in 1..period) {
            val change = closingPrices[i] - closingPrices[i - 1]
            if (change > 0) gains += change
            else losses -= change // Hacemos positivo el número negativo
        }

        var avgGain = gains / period
        var avgLoss = losses / period

        // 2. Suavizar con el resto de datos
        for (i in period + 1 until closingPrices.size) {
            val change = closingPrices[i] - closingPrices[i - 1]
            if (change > 0) {
                avgGain = (avgGain * (period - 1) + change) / period
                avgLoss = (avgLoss * (period - 1)) / period
            } else {
                avgGain = (avgGain * (period - 1)) / period
                avgLoss = (avgLoss * (period - 1) - change) / period
            }
        }

        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100 - (100 / (1 + rs))
    }
}