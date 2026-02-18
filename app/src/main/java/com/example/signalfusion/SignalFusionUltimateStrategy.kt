package com.example.signalfusion

// ==========================================
// 📦 ESTRUCTURAS DE DATOS
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
// 🛡️ GESTOR DE DOBLE CONFIRMACIÓN
// ==========================================

class DoubleConfirmationManager {
    private var lastSignalBar = -1
    private var lastSignalType: String? = null
    private val confirmationBars = 2

    fun shouldAllowSignal(currentBar: Int, signalType: String, oppositeSignal: Boolean): Boolean {
        val barsSinceLastSignal = currentBar - lastSignalBar

        // 1. Evitar cambiar de dirección (Long a Short) demasiado rápido
        if (barsSinceLastSignal < confirmationBars && lastSignalType != null && lastSignalType != signalType) {
            return false
        }

        // 2. Evitar que las dos señales se disparen en la misma vela
        if (oppositeSignal) {
            return false
        }

        return true
    }

    fun registerSignal(currentBar: Int, signalType: String) {
        lastSignalBar = currentBar
        lastSignalType = signalType
    }
}

// ==========================================
// 🧠 MOTOR DE INFERENCIA (SCORING)
// ==========================================

class SignalFusionUltimateStrategy(
    private val timeframe: String = "5m",
    private val mode: String = "AGRESIVA" // "MODERADA", "AGRESIVA", "BREAKOUT"
) {
    val name = "SignalFusion Ultimate V5"
    private val confirmationManager = DoubleConfirmationManager()

    // Memoria del bot (Velas anteriores)
    private var currentBar = 0
    private var pricePrev = 0.0
    private var rsiPrev = 50.0
    private var macdHistPrev = 0.0
    private var emaFastPrev = 0.0
    private var emaSlowPrev = 0.0

    // --- PESOS ADAPTATIVOS POR TIMEFRAME ---
    private fun getWeights(tf: String): Map<String, Double> {
        return when(tf) {
            "1m" -> mapOf("rsi" to 2.2, "ema" to 2.5, "bb" to 1.8, "macd" to 1.9)
            "5m" -> mapOf("rsi" to 2.0, "ema" to 2.3, "bb" to 1.6, "macd" to 1.7)
            "15m" -> mapOf("rsi" to 1.8, "ema" to 2.1, "bb" to 1.4, "macd" to 1.6)
            else -> mapOf("rsi" to 1.7, "ema" to 2.0, "bb" to 1.3, "macd" to 1.5)
        }
    }

    // --- CÁLCULO DE PUNTUACIÓN ---
    private fun calculateScore(data: MarketData): SignalScore {
        val weights = getWeights(timeframe)
        val rsiWeight = weights["rsi"] ?: 1.7
        val emaWeight = weights["ema"] ?: 2.0
        val bbWeight = weights["bb"] ?: 1.3
        val macdWeight = weights["macd"] ?: 1.5

        var longScore = 0.0
        var shortScore = 0.0

        // Variables lógicas de cruce
        val emaFastCrossedOverSlow = emaFastPrev < emaSlowPrev && data.emaFast > data.emaSlow
        val emaFastCrossedUnderSlow = emaFastPrev > emaSlowPrev && data.emaFast < data.emaSlow
        val macdLineCrossedOverSignal = (data.macdLine > data.macdSignal) && (data.macdHist > 0 && macdHistPrev <= 0)
        val macdLineCrossedUnderSignal = (data.macdLine < data.macdSignal) && (data.macdHist < 0 && macdHistPrev >= 0)

        // ==========================================
        // 📈 SCORING PARA COMPRA (LONG)
        // ==========================================

        // 1. RSI
        if (data.rsi < 35) longScore += rsiWeight * 1.0
        if (data.rsi < 45 && data.rsi > rsiPrev && data.rsi > data.rsiMA) longScore += rsiWeight * 0.7

        // 2. EMAs
        if (emaFastCrossedOverSlow) longScore += emaWeight * 1.2
        if (data.emaFast > data.emaSlow && data.price > data.emaFast) longScore += emaWeight * 0.6
        if (data.price > data.emaTrend) longScore += 0.6 // Filtro de tendencia mayor
        if (data.low < data.emaMid && data.price > data.emaMid) longScore += 0.8 // Soporte en EMA 50

        // 3. Bandas de Bollinger
        if (data.price < data.bbLower) longScore += bbWeight * 1.0
        if (data.price < data.bbMiddle && pricePrev < data.bbLower) longScore += bbWeight * 0.8

        // 4. MACD
        if (data.macdHist > 0 && data.macdHist > macdHistPrev) longScore += macdWeight * 0.8
        if (macdLineCrossedOverSignal) longScore += macdWeight * 1.0

        // ==========================================
        // 📉 SCORING PARA VENTA (SHORT)
        // ==========================================

        // 1. RSI
        if (data.rsi > 65) shortScore += rsiWeight * 1.0
        if (data.rsi > 55 && data.rsi < rsiPrev && data.rsi < data.rsiMA) shortScore += rsiWeight * 0.7

        // 2. EMAs
        if (emaFastCrossedUnderSlow) shortScore += emaWeight * 1.2
        if (data.emaFast < data.emaSlow && data.price < data.emaFast) shortScore += emaWeight * 0.6
        if (data.price < data.emaTrend) shortScore += 0.6 // Filtro de tendencia mayor
        if (data.high > data.emaMid && data.price < data.emaMid) shortScore += 0.8 // Resistencia en EMA 50

        // 3. Bandas de Bollinger
        if (data.price > data.bbUpper) shortScore += bbWeight * 1.0
        if (data.price > data.bbMiddle && pricePrev > data.bbUpper) shortScore += bbWeight * 0.8

        // 4. MACD
        if (data.macdHist < 0 && data.macdHist < macdHistPrev) shortScore += macdWeight * 0.8
        if (macdLineCrossedUnderSignal) shortScore += macdWeight * 1.0

        // ==========================================
        // 🎯 UMBRALES DINÁMICOS (THRESHOLDS)
        // ==========================================

        val baseThreshold = when(timeframe) {
            "1m" -> 3.8
            "5m" -> 3.5
            "15m" -> 3.2
            "30m" -> 3.0
            "1h" -> 2.6
            else -> 3.0
        }

        val modeMultiplier = when(mode) {
            "MODERADA" -> 1.3  // Exige más puntos para entrar (Más seguro)
            "AGRESIVA" -> 0.75 // Exige menos puntos (Más entradas)
            else -> 1.0
        }

        val threshold = baseThreshold * modeMultiplier

        return SignalScore(longScore, shortScore, threshold, threshold)
    }

    // --- EVALUACIÓN PÚBLICA (Llamada desde el motor) ---
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

        // Guardar valores en memoria para la próxima vela
        pricePrev = data.price
        rsiPrev = data.rsi
        macdHistPrev = data.macdHist
        emaFastPrev = data.emaFast
        emaSlowPrev = data.emaSlow

        return finalSignal // Retorna "LONG", "SHORT" o "NEUTRAL"
    }
}