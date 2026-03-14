package com.example.signalfusion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 🧠 SIGNAL FUSION V9.4 — SWING 1H MODERADA
 *
 * CAMBIO DE FILOSOFÍA vs V9.3 (scalping 5m):
 *
 * El scalping en 5m con 10x era matemáticamente imposible:
 *   - Fees 1.2% por trade sobre TP de 1.8% = 67% del profit se va en fees
 *   - Win rate necesario > 55%, real fue 20%
 *   - 30 trades/noche erosionaban capital garantizadamente
 *
 * Nueva filosofía — Swing en 1h:
 *   - TP 4.0% / SL 2.0% → R:R 2:1
 *   - Fees 1.2% sobre TP 4.0% = solo 30% del profit en fees
 *   - Win rate necesario > 30% (alcanzable)
 *   - 2-4 trades/día máximo
 *   - MODERADA: exige 2/3 familias → señales más fiables
 *
 * CAMBIOS TÉCNICOS vs V9.3:
 *   - Umbral distToTrend subido a 0.004 (velas 1h tienen más recorrido)
 *   - RSI zonas ajustadas para 1h (menos ruido, señales más limpias)
 *   - MACD expansión umbral bajado a 6% (velas 1h se mueven más despacio)
 *   - minBarsBeforeResignal = 3 barras de 1h = 3 horas mínimo entre señales
 */

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
    val macdHist: Double,
    val htfEmaFast: Double  = 0.0,
    val htfEmaTrend: Double = 0.0,
    val htfMacdHist: Double = 0.0,
    val htfRsi: Double      = 50.0
)

enum class MarketRegime { BULL, BEAR, NEUTRAL }

data class FamilyVote(
    val rsiVote: String,
    val emaVote: String,
    val macdVote: String
)

data class SignalResult(
    val signal: String,
    val regime: MarketRegime,
    val familyVotes: FamilyVote,
    val confirmations: Int,
    val triggerFired: Boolean,
    val rejectionReason: String
)

// ─────────────────────────────────────────────
// GESTOR DE ESTADO
// ─────────────────────────────────────────────

class TradeStateManager {
    private var lastSignalBar    = -1
    private var lastSignalType: String? = null
    private var consecutiveLosses = 0
    private var lastTradeBar     = 0
    private var pairCooldownBars = 0

    // 3 barras de 1h = 3 horas mínimo entre señales del mismo tipo
    private val minBarsBeforeResignal = 3

    fun registerLoss() {
        consecutiveLosses++
        // Tras 2 pérdidas: bloqueo de 6 barras = 6 horas
        if (consecutiveLosses >= 2) pairCooldownBars = 6
    }

    fun registerWin() {
        consecutiveLosses  = 0
        pairCooldownBars   = 0
    }

    fun registerSignal(currentBar: Int, signalType: String) {
        lastSignalBar  = currentBar
        lastSignalType = signalType
        lastTradeBar   = currentBar
        if (pairCooldownBars > 0) pairCooldownBars--
    }

    fun tickBar() { if (pairCooldownBars > 0) pairCooldownBars-- }

    fun canEnter(currentBar: Int, signalType: String): Pair<Boolean, String> {
        if (pairCooldownBars > 0)
            return Pair(false, "Cooldown activo: $pairCooldownBars barras (${consecutiveLosses} pérdidas)")
        val barsSinceLast = currentBar - lastSignalBar
        if (barsSinceLast < minBarsBeforeResignal && lastSignalType != null)
            return Pair(false, "Muy pronto: $barsSinceLast/${minBarsBeforeResignal} barras de 1h")
        if (lastSignalType == signalType && currentBar - lastTradeBar < 2)
            return Pair(false, "Re-entrada inmediata bloqueada ($signalType)")
        return Pair(true, "OK")
    }
}

// ─────────────────────────────────────────────
// ESTRATEGIA PRINCIPAL V9.4
// ─────────────────────────────────────────────

class SignalFusionV9Strategy(
    private val timeframe: String = "1h",
    private val mode: String     = "MODERADA",
    private val useHTF: Boolean  = false
) {
    val name = "SignalFusion V9.4 — Swing 1H 🎯"

    private val stateManager = TradeStateManager()
    private var currentBar   = 0

    private var prevMacdHist    = 0.0
    private var prevRsi         = 50.0
    private var prevEmaFast     = 0.0
    private var prevEmaSlow     = 0.0
    private var prevPrice       = 0.0
    private var prevHtfMacdHist = 0.0
    private var candlesSeen     = 0

    // ─────────────────────────────────────────
    // CAPA 1: RÉGIMEN DE MERCADO
    // ─────────────────────────────────────────

    private fun evaluateRegime(data: MarketData): Pair<MarketRegime, String> {
        // V9.4: umbral 0.004 (antes 0.003) — velas 1h tienen más recorrido
        val distToTrend    = (data.price - data.emaTrend) / data.price
        val isAboveTrend   = distToTrend > 0.004
        val isBelowTrend   = distToTrend < -0.004
        val emaAlignedBull = data.emaFast > data.emaSlow && data.emaSlow > data.emaMid
        val emaAlignedBear = data.emaFast < data.emaSlow && data.emaSlow < data.emaMid

        // BB squeeze: en 1h el umbral sube a 0.008 (las BB son más anchas)
        val bbWidth   = (data.bbUpper - data.bbLower) / data.bbMiddle
        val isRanging = bbWidth < 0.008

        val htfBull: Boolean
        val htfBear: Boolean
        if (useHTF && data.htfEmaTrend > 0.0) {
            htfBull = data.htfEmaFast > data.htfEmaTrend && data.htfMacdHist > 0
            htfBear = data.htfEmaFast < data.htfEmaTrend && data.htfMacdHist < 0
        } else {
            // Sin HTF real: proxy con pendiente de EMA200
            htfBull = distToTrend > 0.008
            htfBear = distToTrend < -0.008
        }

        if (isRanging) return Pair(MarketRegime.NEUTRAL, "BB squeeze (width=${String.format("%.4f", bbWidth)})")

        val bullScore = (if (isAboveTrend) 1 else 0) + (if (emaAlignedBull) 1 else 0) + (if (htfBull) 1 else 0)
        val bearScore = (if (isBelowTrend) 1 else 0) + (if (emaAlignedBear) 1 else 0) + (if (htfBear) 1 else 0)

        return when {
            bullScore >= 2 && bullScore > bearScore -> Pair(MarketRegime.BULL, "Bull ($bullScore/3)")
            bearScore >= 2 && bearScore > bullScore -> Pair(MarketRegime.BEAR, "Bear ($bearScore/3)")
            else -> Pair(MarketRegime.NEUTRAL, "Sin tendencia clara (bull=$bullScore bear=$bearScore)")
        }
    }

    // ─────────────────────────────────────────
    // CAPA 2: VOTACIÓN POR FAMILIAS
    // ─────────────────────────────────────────

    private fun evaluateFamilies(data: MarketData): FamilyVote {
        val hasPrev = candlesSeen >= 2

        // ── FAMILIA RSI ──
        // V9.4: en 1h el RSI tiene más rango útil
        // LONG: RSI recuperando desde zona 35-55 con momentum
        // SHORT: RSI cayendo desde zona 45-65 con momentum
        val rsiVote = when {
            hasPrev && data.rsi in 35.0..55.0
                    && data.rsi > prevRsi + 1.5
                    && data.rsi > data.rsiMA -> "LONG"

            hasPrev && data.rsi < 35.0
                    && data.rsi > prevRsi
                    && data.rsi > data.rsiMA -> "LONG"

            hasPrev && data.rsi in 45.0..65.0
                    && data.rsi < prevRsi - 1.5
                    && data.rsi < data.rsiMA -> "SHORT"

            hasPrev && data.rsi > 65.0
                    && data.rsi < prevRsi
                    && data.rsi < data.rsiMA -> "SHORT"

            else -> "NEUTRAL"
        }

        // ── FAMILIA EMA ──
        val emaCrossUp   = hasPrev && prevEmaFast < prevEmaSlow && data.emaFast >= data.emaSlow
        val emaCrossDown = hasPrev && prevEmaFast > prevEmaSlow && data.emaFast <= data.emaSlow
        val emaVote = when {
            emaCrossUp -> "LONG"
            data.emaFast > data.emaSlow && data.emaSlow > data.emaMid
                    && data.price > data.emaFast * 1.001 -> "LONG"
            emaCrossDown -> "SHORT"
            data.emaFast < data.emaSlow && data.emaSlow < data.emaMid
                    && data.price < data.emaFast * 0.999 -> "SHORT"
            else -> "NEUTRAL"
        }

        // ── FAMILIA MACD ──
        // Sin fallback estático — solo cruces y expansión real vela-a-vela
        // V9.4: umbral expansión 6% (antes 8%) — velas 1h se mueven más despacio
        val macdCrossUp       = hasPrev && data.macdLine >  data.macdSignal
                && data.macdHist > 0 && prevMacdHist <= 0
        val macdCrossDown     = hasPrev && data.macdLine <  data.macdSignal
                && data.macdHist < 0 && prevMacdHist >= 0
        val macdExpandingUp   = hasPrev && data.macdHist > 0 && prevMacdHist > 0
                && data.macdHist > prevMacdHist * 1.06
        val macdExpandingDown = hasPrev && data.macdHist < 0 && prevMacdHist < 0
                && data.macdHist < prevMacdHist * 1.06

        val macdVote = when {
            macdCrossUp   || macdExpandingUp   -> "LONG"
            macdCrossDown || macdExpandingDown -> "SHORT"
            else -> "NEUTRAL"
        }

        return FamilyVote(rsiVote, emaVote, macdVote)
    }

    // ─────────────────────────────────────────
    // CAPA 3: TRIGGER DE ENTRADA
    // ─────────────────────────────────────────

    private fun checkTrigger(data: MarketData, direction: String): Pair<Boolean, String> {
        return when (direction) {
            "LONG" -> {
                val priceAboveEmaFast = data.price > data.emaFast
                val macdPositive      = data.macdHist > 0
                when {
                    !priceAboveEmaFast -> Pair(false, "Precio bajo EMA rápida")
                    !macdPositive      -> Pair(false, "MACD negativo")
                    else               -> Pair(true,  "Trigger LONG ✅")
                }
            }
            "SHORT" -> {
                val priceBelowEmaFast = data.price < data.emaFast
                val macdNegative      = data.macdHist < 0
                when {
                    !priceBelowEmaFast -> Pair(false, "Precio sobre EMA rápida")
                    !macdNegative      -> Pair(false, "MACD positivo")
                    else               -> Pair(true,  "Trigger SHORT ✅")
                }
            }
            else -> Pair(false, "Sin dirección")
        }
    }

    // ─────────────────────────────────────────
    // EVALUACIÓN PRINCIPAL
    // ─────────────────────────────────────────

    fun evaluate(data: MarketData, isNewCandle: Boolean = false): SignalResult {
        currentBar++
        stateManager.tickBar()

        val (regime, regimeReason) = evaluateRegime(data)
        if (regime == MarketRegime.NEUTRAL) {
            if (isNewCandle) updateMemory(data)
            return SignalResult("NEUTRAL", regime,
                FamilyVote("NEUTRAL","NEUTRAL","NEUTRAL"), 0, false,
                "Capa 1 - NEUTRAL: $regimeReason")
        }

        val votes            = evaluateFamilies(data)
        val allowedDirection = if (regime == MarketRegime.BULL) "LONG" else "SHORT"
        val confirmations    = listOf(votes.rsiVote, votes.emaVote, votes.macdVote)
            .count { it == allowedDirection }

        // MODERADA: exige 2/3 familias — señales más fiables que AGRESIVA
        val minConfirmations = if (mode == "AGRESIVA") 1 else 2

        if (confirmations < minConfirmations) {
            if (isNewCandle) updateMemory(data)
            return SignalResult("NEUTRAL", regime, votes, confirmations, false,
                "Capa 2 - $confirmations/$minConfirmations ($allowedDirection) " +
                        "RSI=${votes.rsiVote} EMA=${votes.emaVote} MACD=${votes.macdVote}")
        }

        val (triggerOk, triggerReason) = checkTrigger(data, allowedDirection)
        if (!triggerOk) {
            if (isNewCandle) updateMemory(data)
            return SignalResult("NEUTRAL", regime, votes, confirmations, false,
                "Capa 3 - $triggerReason")
        }

        val (canEnter, enterReason) = stateManager.canEnter(currentBar, allowedDirection)
        if (!canEnter) {
            if (isNewCandle) updateMemory(data)
            return SignalResult("NEUTRAL", regime, votes, confirmations, true,
                "Estado - $enterReason")
        }

        stateManager.registerSignal(currentBar, allowedDirection)
        if (isNewCandle) updateMemory(data)
        return SignalResult(allowedDirection, regime, votes, confirmations, true, "OK")
    }

    fun reportResult(wasWin: Boolean) {
        if (wasWin) stateManager.registerWin() else stateManager.registerLoss()
    }

    private fun updateMemory(data: MarketData) {
        prevMacdHist    = data.macdHist
        prevRsi         = data.rsi
        prevEmaFast     = data.emaFast
        prevEmaSlow     = data.emaSlow
        prevPrice       = data.price
        prevHtfMacdHist = data.htfMacdHist
        candlesSeen++
    }
}

// ─────────────────────────────────────────────
// PARÁMETROS V9.4 — SWING 1H
// ─────────────────────────────────────────────

/**
 * Matemática de rentabilidad con estos parámetros:
 *
 * Fees por trade: 0.06% apertura + 0.06% cierre × 5x = 0.6%
 * TP neto real:   4.0% - 0.6% fees = 3.4%
 * SL neto real:  -2.0% - 0.6% fees = -2.6%
 * R:R real:       3.4 / 2.6 = 1.31:1
 *
 * Con win rate 40%:
 *   (0.40 × 3.4) - (0.60 × 2.6) = 1.36 - 1.56 = -0.20% → break-even ~40%
 *
 * Con win rate 45%:
 *   (0.45 × 3.4) - (0.55 × 2.6) = 1.53 - 1.43 = +0.10% por trade ✅
 *
 * Break-even mínimo: ~41% win rate
 * Objetivo realista: 45-55% con MODERADA en 1h
 */
object V9TradingParams {
    const val TP_SCALPING         = 4.0   // antes 1.8 — target alcanzable en 1h
    const val SL_SCALPING         = 2.0   // antes 1.5 — SL respirable en 1h
    const val TRAILING_ACTIVATION = 3.0   // antes 1.4 — activar trailing en 3%
    const val TRAILING_CALLBACK   = 1.0   // antes 0.6 — cerrar si retrocede 1%
    const val TP_SWING            = 6.0
    const val SL_SWING            = 2.5
}