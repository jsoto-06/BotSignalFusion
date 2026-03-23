package com.example.signalfusion

import kotlin.math.abs

/**
 * 🧠 SIGNAL FUSION V9.6 — FRANCOTIRADOR MTFA
 *
 * CAMBIO ARQUITECTURAL vs V9.5:
 *
 * Antes: todas las capas usaban el mismo historial (1H)
 * Ahora: cada capa usa la temporalidad correcta
 *
 *   CAPA 1 — Régimen HTF (1H):
 *     Lee EMA200, MACD y BB de velas de 1H
 *     Determina BULL/BEAR/NEUTRAL con alta fiabilidad
 *     Solo BULL = solo LONGs · Solo BEAR = solo SHORTs
 *
 *   CAPAS 2/3 — Entrada LTF (15m):
 *     Lee RSI, EMA, MACD de velas de 15m
 *     Busca el momento exacto de entrada en la dirección del HTF
 *     2/3 familias en 15m alineadas con HTF = señal válida
 *
 *   CAPA 4 — Timing RT (Order Flow):
 *     Lee trades individuales en tiempo real
 *     Confirma que el dinero real está entrando en esa dirección
 *
 * RESULTADO:
 *   1H dice "el mercado es bajista" (mapa)
 *   15m dice "ahora mismo está cayendo con momentum" (lupa)
 *   Order Flow dice "los vendedores están atacando ahora" (gatillo)
 *   → ENTRAR SHORT con máxima convicción
 */

data class MarketData(
    // ── LTF (15m) — para Capas 2 y 3 ──
    val price: Double,
    val high: Double,
    val low: Double,
    val rsi: Double,
    val rsiMA: Double,
    val emaFast: Double,    // EMA 12 en 15m
    val emaSlow: Double,    // EMA 26 en 15m
    val emaMid: Double,     // EMA 50 en 15m
    val emaTrend: Double,   // EMA 200 en 15m (referencia)
    val bbUpper: Double,
    val bbMiddle: Double,
    val bbLower: Double,
    val macdLine: Double,
    val macdSignal: Double,
    val macdHist: Double,
    // ── HTF (1H) — para Capa 1 ──
    // Siempre deben estar presentes en V9.6
    val htfEmaFast: Double  = 0.0,   // EMA 12 en 1H
    val htfEmaSlow: Double  = 0.0,   // EMA 26 en 1H
    val htfEmaMid: Double   = 0.0,   // EMA 50 en 1H
    val htfEmaTrend: Double = 0.0,   // EMA 200 en 1H
    val htfMacdHist: Double = 0.0,   // MACD hist en 1H
    val htfBbWidth: Double  = 0.0,   // BB width en 1H
    val htfRsi: Double      = 50.0,  // RSI en 1H
    // ── Order Flow — para Capa 4 ──
    val orderFlow: OrderFlowData? = null
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
    private val minBarsBeforeResignal = 3

    fun registerLoss() {
        consecutiveLosses++
        if (consecutiveLosses >= 2) pairCooldownBars = 6
    }
    fun registerWin() { consecutiveLosses = 0; pairCooldownBars = 0 }
    fun registerSignal(bar: Int, type: String) {
        lastSignalBar = bar; lastSignalType = type; lastTradeBar = bar
        if (pairCooldownBars > 0) pairCooldownBars--
    }
    fun tickBar() { if (pairCooldownBars > 0) pairCooldownBars-- }
    fun canEnter(bar: Int, type: String): Pair<Boolean, String> {
        if (pairCooldownBars > 0) return Pair(false, "Cooldown $pairCooldownBars barras")
        val since = bar - lastSignalBar
        if (since < minBarsBeforeResignal && lastSignalType != null)
            return Pair(false, "Muy pronto: $since/${minBarsBeforeResignal} barras LTF")
        if (lastSignalType == type && bar - lastTradeBar < 2)
            return Pair(false, "Re-entrada inmediata bloqueada ($type)")
        return Pair(true, "OK")
    }
}

// ─────────────────────────────────────────────
// ESTRATEGIA PRINCIPAL V9.6
// ─────────────────────────────────────────────

class SignalFusionV9Strategy(
    private val ltfTimeframe: String = "15m",  // LTF para entrada
    private val htfTimeframe: String = "1h",   // HTF para dirección
    private val mode: String         = "MODERADA"
) {
    val name = "SignalFusion V9.6 — MTFA Francotirador 🎯"

    private val stateManager = TradeStateManager()
    private var currentBar   = 0

    // Memoria de la vela LTF anterior
    private var prevMacdHist = 0.0
    private var prevRsi      = 50.0
    private var prevEmaFast  = 0.0
    private var prevEmaSlow  = 0.0
    private var candlesSeen  = 0

    // Memoria de la vela HTF anterior
    private var prevHtfMacdHist = 0.0
    private var prevHtfEmaFast  = 0.0

    // ─────────────────────────────────────────
    // CAPA 1: RÉGIMEN HTF (1H)
    // Lee exclusivamente los datos de 1H para determinar la tendencia
    // ─────────────────────────────────────────

    private fun evaluateRegime(data: MarketData): Pair<MarketRegime, String> {

        // Si no hay datos HTF reales, usar LTF como fallback (modo degradado)
        val hasHtfData = data.htfEmaTrend > 0.0

        if (!hasHtfData) {
            // Fallback: usar EMA200 del LTF con umbral más estricto
            val dist = (data.price - data.emaTrend) / data.price
            val bbWidth = (data.bbUpper - data.bbLower) / data.bbMiddle
            if (bbWidth < 0.005) return Pair(MarketRegime.NEUTRAL, "BB squeeze LTF (fallback)")
            return when {
                dist > 0.005 && data.emaFast > data.emaSlow -> Pair(MarketRegime.BULL, "Bull LTF fallback")
                dist < -0.005 && data.emaFast < data.emaSlow -> Pair(MarketRegime.BEAR, "Bear LTF fallback")
                else -> Pair(MarketRegime.NEUTRAL, "Sin tendencia LTF (fallback)")
            }
        }

        // ── Análisis HTF real (1H) ──

        // BB width en 1H — squeeze = mercado en rango, no operar
        if (data.htfBbWidth > 0.0 && data.htfBbWidth < 0.008) {
            return Pair(MarketRegime.NEUTRAL, "BB squeeze 1H (width=${String.format("%.4f", data.htfBbWidth)})")
        }

        // Distancia al EMA200 en 1H
        val distHtf = (data.price - data.htfEmaTrend) / data.price
        val isAboveHtfTrend = distHtf > 0.003
        val isBelowHtfTrend = distHtf < -0.003

        // Alineación EMAs en 1H
        val htfEmasBull = data.htfEmaFast > data.htfEmaSlow &&
                data.htfEmaSlow > data.htfEmaMid
        val htfEmasBear = data.htfEmaFast < data.htfEmaSlow &&
                data.htfEmaSlow < data.htfEmaMid

        // MACD en 1H positivo/negativo
        val htfMacdBull = data.htfMacdHist > 0
        val htfMacdBear = data.htfMacdHist < 0

        val bullScore = (if (isAboveHtfTrend) 1 else 0) +
                (if (htfEmasBull) 1 else 0) +
                (if (htfMacdBull) 1 else 0)

        val bearScore = (if (isBelowHtfTrend) 1 else 0) +
                (if (htfEmasBear) 1 else 0) +
                (if (htfMacdBear) 1 else 0)

        return when {
            bullScore >= 2 && bullScore > bearScore ->
                Pair(MarketRegime.BULL, "Bull 1H ($bullScore/3) dist=${String.format("%.3f", distHtf * 100)}%")
            bearScore >= 2 && bearScore > bullScore ->
                Pair(MarketRegime.BEAR, "Bear 1H ($bearScore/3) dist=${String.format("%.3f", distHtf * 100)}%")
            else ->
                Pair(MarketRegime.NEUTRAL, "Sin tendencia 1H (bull=$bullScore bear=$bearScore)")
        }
    }

    // ─────────────────────────────────────────
    // CAPA 2: FAMILIAS LTF (15m)
    // Lee los indicadores de 15m para timing de entrada
    // ─────────────────────────────────────────

    private fun evaluateFamilies(data: MarketData): FamilyVote {
        val hasPrev = candlesSeen >= 2

        // RSI 15m — momentum vela-a-vela en LTF
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

        // EMA 15m — cruces y alineación en LTF
        val crossUp   = hasPrev && prevEmaFast < prevEmaSlow && data.emaFast >= data.emaSlow
        val crossDown = hasPrev && prevEmaFast > prevEmaSlow && data.emaFast <= data.emaSlow
        val emaVote = when {
            crossUp -> "LONG"
            data.emaFast > data.emaSlow && data.emaSlow > data.emaMid
                    && data.price > data.emaFast * 1.001 -> "LONG"
            crossDown -> "SHORT"
            data.emaFast < data.emaSlow && data.emaSlow < data.emaMid
                    && data.price < data.emaFast * 0.999 -> "SHORT"
            else -> "NEUTRAL"
        }

        // MACD 15m — cruce y expansión vela-a-vela en LTF
        val macdCrossUp   = hasPrev && data.macdLine > data.macdSignal && data.macdHist > 0 && prevMacdHist <= 0
        val macdCrossDown = hasPrev && data.macdLine < data.macdSignal && data.macdHist < 0 && prevMacdHist >= 0
        val macdExpUp     = hasPrev && data.macdHist > 0 && prevMacdHist > 0 && data.macdHist > prevMacdHist * 1.06
        val macdExpDown   = hasPrev && data.macdHist < 0 && prevMacdHist < 0 && data.macdHist < prevMacdHist * 1.06

        val macdVote = when {
            macdCrossUp || macdExpUp     -> "LONG"
            macdCrossDown || macdExpDown -> "SHORT"
            else -> "NEUTRAL"
        }

        return FamilyVote(rsiVote, emaVote, macdVote)
    }

    // ─────────────────────────────────────────
    // CAPA 3: TRIGGER LTF (15m)
    // ─────────────────────────────────────────

    private fun checkTrigger(data: MarketData, direction: String): Pair<Boolean, String> {
        return when (direction) {
            "LONG" -> when {
                data.price <= data.emaFast -> Pair(false, "Precio bajo EMA rápida 15m")
                data.macdHist <= 0         -> Pair(false, "MACD 15m negativo")
                else                       -> Pair(true,  "Trigger LONG 15m ✅")
            }
            "SHORT" -> when {
                data.price >= data.emaFast -> Pair(false, "Precio sobre EMA rápida 15m")
                data.macdHist >= 0         -> Pair(false, "MACD 15m positivo")
                else                       -> Pair(true,  "Trigger SHORT 15m ✅")
            }
            else -> Pair(false, "Sin dirección")
        }
    }

    // ─────────────────────────────────────────
    // CAPA 4: ORDER FLOW (tiempo real)
    // ─────────────────────────────────────────

    private fun checkOrderFlow(data: MarketData, direction: String): Pair<Boolean, String> {
        val of = data.orderFlow ?: return Pair(true, "OF no disponible — permitido")
        return OrderFlowAnalyzer().confirmSignal(direction, of)
    }

    // ─────────────────────────────────────────
    // EVALUACIÓN PRINCIPAL
    // ─────────────────────────────────────────

    fun evaluate(data: MarketData, isNewCandle: Boolean = false): SignalResult {
        currentBar++
        stateManager.tickBar()

        // CAPA 1 — HTF
        val (regime, regimeReason) = evaluateRegime(data)
        if (regime == MarketRegime.NEUTRAL) {
            if (isNewCandle) updateMemory(data)
            return SignalResult("NEUTRAL", regime,
                FamilyVote("N","N","N"), 0, false,
                "Capa 1 HTF - NEUTRAL: $regimeReason")
        }

        // CAPA 2 — LTF
        val votes = evaluateFamilies(data)
        val dir   = if (regime == MarketRegime.BULL) "LONG" else "SHORT"
        val confs = listOf(votes.rsiVote, votes.emaVote, votes.macdVote).count { it == dir }
        val minC  = if (mode == "AGRESIVA") 1 else 2

        if (confs < minC) {
            if (isNewCandle) updateMemory(data)
            return SignalResult("NEUTRAL", regime, votes, confs, false,
                "Capa 2 LTF - $confs/$minC ($dir) RSI=${votes.rsiVote} EMA=${votes.emaVote} MACD=${votes.macdVote}")
        }

        // CAPA 3 — LTF trigger
        val (trigOk, trigReason) = checkTrigger(data, dir)
        if (!trigOk) {
            if (isNewCandle) updateMemory(data)
            return SignalResult("NEUTRAL", regime, votes, confs, false, "Capa 3 LTF - $trigReason")
        }

        // CAPA 4 — Order Flow
        val (ofOk, ofReason) = checkOrderFlow(data, dir)
        if (!ofOk) {
            if (isNewCandle) updateMemory(data)
            return SignalResult("NEUTRAL", regime, votes, confs, true,
                "Capa 4 OF - $ofReason")
        }

        // Estado
        val (canEnter, enterReason) = stateManager.canEnter(currentBar, dir)
        if (!canEnter) {
            if (isNewCandle) updateMemory(data)
            return SignalResult("NEUTRAL", regime, votes, confs, true, "Estado - $enterReason")
        }

        stateManager.registerSignal(currentBar, dir)
        if (isNewCandle) updateMemory(data)
        return SignalResult(dir, regime, votes, confs, true, "OK — $ofReason")
    }

    fun reportResult(wasWin: Boolean) {
        if (wasWin) stateManager.registerWin() else stateManager.registerLoss()
    }

    private fun updateMemory(data: MarketData) {
        prevMacdHist    = data.macdHist
        prevRsi         = data.rsi
        prevEmaFast     = data.emaFast
        prevEmaSlow     = data.emaSlow
        prevHtfMacdHist = data.htfMacdHist
        prevHtfEmaFast  = data.htfEmaFast
        candlesSeen++
    }
}

// ─────────────────────────────────────────────
// PARÁMETROS
// ─────────────────────────────────────────────

object V9TradingParams {
    const val TP_SCALPING         = 4.0
    const val SL_SCALPING         = 2.0
    const val TRAILING_ACTIVATION = 3.0
    const val TRAILING_CALLBACK   = 1.0
    const val TP_SWING            = 6.0
    const val SL_SWING            = 2.5
}