package com.example.signalfusion

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object Indicadores {

    // ==========================================
    // 1. INDICADORES BASE (Tu código original)
    // ==========================================

    fun calcularRSI(datos: List<Double>, periodo: Int): Double {
        if (datos.size < periodo + 1) return 50.0

        var ganancias = 0.0
        var perdidas = 0.0

        for (i in 1..periodo) {
            val diferencia = datos[i] - datos[i - 1]
            if (diferencia > 0) ganancias += diferencia
            else perdidas += abs(diferencia)
        }

        var gananciaMedia = ganancias / periodo
        var perdidaMedia = perdidas / periodo

        for (i in periodo + 1 until datos.size) {
            val diferencia = datos[i] - datos[i - 1]
            val gananciaActual = if (diferencia > 0) diferencia else 0.0
            val perdidaActual = if (diferencia < 0) abs(diferencia) else 0.0

            gananciaMedia = (gananciaMedia * (periodo - 1) + gananciaActual) / periodo
            perdidaMedia = (perdidaMedia * (periodo - 1) + perdidaActual) / periodo
        }

        if (perdidaMedia == 0.0) return 100.0

        val rs = gananciaMedia / perdidaMedia
        return 100.0 - (100.0 / (1.0 + rs))
    }

    fun calcularEMA(datos: List<Double>, periodo: Int): Double {
        if (datos.isEmpty()) return 0.0
        if (datos.size < periodo) return datos.last()

        val multiplicador = 2.0 / (periodo + 1)
        var ema = datos.take(periodo).average()

        for (i in periodo until datos.size) {
            ema = (datos[i] - ema) * multiplicador + ema
        }

        return ema
    }

    fun calcularATR(preciosCierre: List<Double>, periodo: Int = 14): Double {
        if (preciosCierre.size < periodo + 1) return 0.0

        val rangosVerdaderos = mutableListOf<Double>()

        for (i in 1 until preciosCierre.size) {
            val high = preciosCierre[i] * 1.0025
            val low = preciosCierre[i] * 0.9975
            val prevClose = preciosCierre[i - 1]

            val tr = maxOf(
                high - low,
                abs(high - prevClose),
                abs(low - prevClose)
            )
            rangosVerdaderos.add(tr)
        }

        return if (rangosVerdaderos.size >= periodo) {
            rangosVerdaderos.takeLast(periodo).average()
        } else {
            rangosVerdaderos.average()
        }
    }

    // ==========================================
    // 2. INDICADORES ULTIMATE (Nuevos para Fase 2)
    // ==========================================

    data class EMAs(val fast: Double, val slow: Double, val mid: Double, val trend: Double)

    fun calcularEMAs(datos: List<Double>): EMAs {
        return EMAs(
            fast = calcularEMA(datos, 12),
            slow = calcularEMA(datos, 26),
            mid = calcularEMA(datos, 50),
            trend = calcularEMA(datos, 200)
        )
    }

    fun calcularBollingerBands(datos: List<Double>, periodo: Int = 20, desviaciones: Double = 2.0): Triple<Double, Double, Double> {
        if (datos.size < periodo) return Triple(0.0, 0.0, 0.0)

        val subset = datos.takeLast(periodo)
        val sma = subset.average()

        val variance = subset.map { (it - sma).pow(2) }.average()
        val stdDev = sqrt(variance)

        val upper = sma + (stdDev * desviaciones)
        val lower = sma - (stdDev * desviaciones)

        return Triple(upper, sma, lower) // Triple(Superior, Media, Inferior)
    }

    fun calcularRSIMA(datos: List<Double>, rsiPeriodo: Int = 14, maPeriodo: Int = 7): Double {
        if (datos.size < rsiPeriodo + maPeriodo) return 50.0

        val rsiHistory = mutableListOf<Double>()
        for (i in (datos.size - maPeriodo) until datos.size) {
            val subset = datos.subList(0, i + 1)
            rsiHistory.add(calcularRSI(subset, rsiPeriodo))
        }

        return rsiHistory.average()
    }

    fun calcularMACD(datos: List<Double>, fastPeriod: Int = 12, slowPeriod: Int = 26, signalPeriod: Int = 9): Triple<Double, Double, Double> {
        if (datos.size < slowPeriod + signalPeriod) return Triple(0.0, 0.0, 0.0)

        // 1. Array histórico de la línea MACD
        val macdLineValues = mutableListOf<Double>()
        for (i in slowPeriod..datos.size) {
            val subset = datos.subList(0, i)
            val emaFast = calcularEMA(subset, fastPeriod)
            val emaSlow = calcularEMA(subset, slowPeriod)
            macdLineValues.add(emaFast - emaSlow)
        }

        val macdLineActual = macdLineValues.last()

        // 2. Línea Signal (EMA de la línea MACD)
        val signalLine = calcularEMA(macdLineValues, signalPeriod)

        // 3. Histograma
        val histograma = macdLineActual - signalLine

        return Triple(macdLineActual, signalLine, histograma)
    }
}