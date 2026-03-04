package com.example.signalfusion

import kotlin.math.abs

// ==========================================
// 📦 ESTRUCTURAS DE DATOS (100% compatibles con tu app)
// ==========================================
data class MarketData(
    val price: Double,
    val high: Double,
    val low: Double,
    val rsi: Double,
    val rsiMA: Double,
    val emaFast: Double,
    val emaSlow: Double,
    val emaMid: Double,
    val emaTrend: Double,
    val bbUpper: Double,
    val bbMiddle: Double,
    val bbLower: Double,
    val macdLine: Double,
    val macdSignal: Double,
    val macdHist: Double
)

data class SignalScore(
    val longScore: Double,
    val shortScore: Double,
    val longThreshold: Double,
    val shortThreshold: Double
)

// ==========================================
// 🛡️ GESTOR DE DOBLE CONFIRMACIÓN (intacto)
// ==========================================
class DoubleConfirmationManager {
    private var lastSignalBar = -1
    private var lastSignalType: String? = null
    private val confirmationBars = 2

    fun shouldAllowSignal(currentBar: Int, signalType: String, oppositeSignal: Boolean): Boolean {
        val barsSinceLastSignal = currentBar - lastSignalBar
        if (barsSinceLastSignal < confirmationBars && lastSignalType != null && lastSignalType != signalType) return false
        if (oppositeSignal) return false
        return true
    }

    fun registerSignal(currentBar: Int, signalType: String) {
        lastSignalBar = currentBar
        lastSignalType = signalType
    }
}

// ==========================================
// 🧠 SIGNAL FUSION ULTIMATE V7 MADRE (LA DEFINITIVA)
// ==========================================
class SignalFusionUltimateStrategy(
    private val timeframe: String = "5m",
    private val mode: String = "AGRESIVA" // MODERADA / AGRESIVA / BREAKOUT
) {
    val name = "SignalFusion Ultimate V7 MADRE 🔥"

    private val confirmationManager = DoubleConfirmationManager()

    // Memoria
    private var currentBar = 0
    private var pricePrev = 0.0
    private var rsiPrev = 50.0
    private var macdHistPrev = 0.0
    private var emaFastPrev = 0.0
    private var emaSlowPrev = 0.0
    private var rangePrev = 0.0     // Para detectar velas explosivas (Powell moments)

    private fun getWeights(tf: String): Map<String, Double> {
        return when (tf) {
            "1m"  -> mapOf("rsi" to 2.1, "ema" to 2.4, "bb" to 1.9, "macd" to 1.8)
            "5m"  -> mapOf("rsi" to 2.0, "ema" to 2.3, "bb" to 1.7, "macd" to 1.7)
            "15m" -> mapOf("rsi" to 1.8, "ema" to 2.1, "bb" to 1.5, "macd" to 1.6)
            else  -> mapOf("rsi" to 1.7, "ema" to 2.0, "bb" to 1.4, "macd" to 1.5)
        }
    }

    private fun calculateScore(data: MarketData): SignalScore {
        val weights = getWeights(timeframe)
        val rsiW = weights["rsi"]!!
        val emaW = weights["ema"]!!
        val bbW = weights["bb"]!!
        val macdW = weights["macd"]!!

        var longScore = 0.0
        var shortScore = 0.0

        // ==========================================
        // 🔬 ANÁLISIS DE ENTORNO (mejor proxy sin ATR/ADX)
        // ==========================================
        val bbWidth = (data.bbUpper - data.bbLower) / data.bbMiddle
        val isSqueeze = bbWidth < 0.013

        // Filtro anti-Powell: vela explosiva real (mejor que BB width solo)
        val candleRange = (data.high - data.low) / data.price
        val isHighVolatility = currentBar > 5 && candleRange > rangePrev * 1.45

        // Proxy de fuerza de tendencia (más preciso que Gemini)
        val emaSeparation = abs(data.emaFast - data.emaSlow) / data.price
        val isStrongTrend = emaSeparation > 0.0018 || abs(data.price - data.emaTrend) / data.price > 0.012

        // Cruces reales
        val emaCrossUp = emaFastPrev < emaSlowPrev && data.emaFast > data.emaSlow
        val emaCrossDown = emaFastPrev > emaSlowPrev && data.emaFast < data.emaSlow
        val macdCrossUp = data.macdLine > data.macdSignal && data.macdHist > 0 && macdHistPrev <= 0
        val macdCrossDown = data.macdLine < data.macdSignal && data.macdHist < 0 && macdHistPrev >= 0

        // ==========================================
        // 🛑 FILTROS DE SEGURIDAD
        // ==========================================
        if (isHighVolatility) {
            longScore -= 1.8
            shortScore -= 1.8
        }

        if (data.price < data.emaSlow) longScore -= if (isStrongTrend) 2.6 else 1.6
        if (data.price > data.emaSlow) shortScore -= if (isStrongTrend) 2.6 else 1.6

        // ==========================================
        // 📈 SCORING LONG
        // ==========================================
        if (data.rsi < 35) longScore += rsiW * 1.1
        if (data.rsi in 35.0..45.0 && data.rsi > rsiPrev && data.rsi > data.rsiMA) longScore += rsiW * 0.8

        if (emaCrossUp) longScore += emaW * 1.35
        if (data.emaFast > data.emaSlow && data.price > data.emaFast) longScore += emaW * 0.7
        if (data.price > data.emaTrend) longScore += 0.85
        if (data.low < data.emaMid && data.price > data.emaMid) longScore += 0.95

        if (data.price < data.bbLower) longScore += bbW * 1.1
        if (isSqueeze && data.price < data.bbMiddle) longScore += bbW * 1.55   // Squeeze explosivo

        if (data.macdHist > 0 && data.macdHist > macdHistPrev) longScore += macdW * 0.9
        if (macdCrossUp) longScore += macdW * 1.25

        // ==========================================
        // 📉 SCORING SHORT (simétrico)
        // ==========================================
        if (data.rsi > 65) shortScore += rsiW * 1.1
        if (data.rsi in 55.0..65.0 && data.rsi < rsiPrev && data.rsi < data.rsiMA) shortScore += rsiW * 0.8

        if (emaCrossDown) shortScore += emaW * 1.35
        if (data.emaFast < data.emaSlow && data.price < data.emaFast) shortScore += emaW * 0.7
        if (data.price < data.emaTrend) shortScore += 0.85
        if (data.high > data.emaMid && data.price < data.emaMid) shortScore += 0.95

        if (data.price > data.bbUpper) shortScore += bbW * 1.1
        if (isSqueeze && data.price > data.bbMiddle) shortScore += bbW * 1.55

        if (data.macdHist < 0 && data.macdHist < macdHistPrev) shortScore += macdW * 0.9
        if (macdCrossDown) shortScore += macdW * 1.25

        // ==========================================
        // 🎯 UMBRALES + MODO + SESGO PROFESIONAL
        // ==========================================
        val baseThreshold = when (timeframe) {
            "1m" -> 3.9
            "5m" -> 3.5
            "15m" -> 3.2
            else -> 2.9
        }

        val modeMult = when (mode) {
            "MODERADA" -> 1.16
            "AGRESIVA" -> 0.83
            "BREAKOUT" -> 0.78
            else       -> 1.0
        }
        var threshold = baseThreshold * modeMult

        if (mode == "BREAKOUT" && isSqueeze) threshold *= 0.82

        // Sesgo dinámico suave y profesional (mejor que Gemini)
        val bullishBias = data.price > data.emaTrend * 1.008 && isStrongTrend
        val bearishBias = data.price < data.emaTrend * 0.992 && isStrongTrend

        when {
            bullishBias -> { longScore *= 1.18; shortScore *= 0.82 }
            bearishBias -> { shortScore *= 1.18; longScore *= 0.82 }
            else        -> threshold *= 1.08   // Mercado rango = más exigente
        }

        return SignalScore(longScore, shortScore, threshold, threshold)
    }

    fun evaluate(data: MarketData): String {
        currentBar++

        val score = calculateScore(data)

        val rawLong = score.longScore >= score.longThreshold
        val rawShort = score.shortScore >= score.shortThreshold

        val isLong = rawLong && confirmationManager.shouldAllowSignal(currentBar, "LONG", rawShort)
        val isShort = rawShort && confirmationManager.shouldAllowSignal(currentBar, "SHORT", rawLong)

        var finalSignal = "NEUTRAL"

        if (isLong) {
            confirmationManager.registerSignal(currentBar, "LONG")
            finalSignal = "LONG"
        } else if (isShort) {
            confirmationManager.registerSignal(currentBar, "SHORT")
            finalSignal = "SHORT"
        }

        // Actualizar memoria
        pricePrev = data.price
        rsiPrev = data.rsi
        macdHistPrev = data.macdHist
        emaFastPrev = data.emaFast
        emaSlowPrev = data.emaSlow
        rangePrev = (data.high - data.low) / data.price

        return finalSignal
    }
}