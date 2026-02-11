package com.example.bitgetbot

import kotlin.math.abs

object Indicadores {

    /**
     * Calcula el RSI (Relative Strength Index)
     * Utilizado para detectar sobreventa (compra) o sobrecompra (venta)
     */
    fun calcularRSI(datos: List<Double>, periodo: Int): Double {
        if (datos.size < periodo + 1) return 50.0

        var ganancias = 0.0
        var perdidas = 0.0

        // Primera iteración para establecer el promedio inicial
        for (i in 1..periodo) {
            val diferencia = datos[i] - datos[i - 1]
            if (diferencia > 0) {
                ganancias += diferencia
            } else {
                perdidas += abs(diferencia)
            }
        }

        var gananciaMedia = ganancias / periodo
        var perdidaMedia = perdidas / periodo

        // Suavizado de Wilder para el resto de los datos
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

    /**
     * Calcula la EMA (Media Móvil Exponencial)
     * Ayuda a identificar la dirección de la tendencia
     */
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

    /**
     * Calcula el ATR (Average True Range)
     * Mide la volatilidad del mercado
     */
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
}