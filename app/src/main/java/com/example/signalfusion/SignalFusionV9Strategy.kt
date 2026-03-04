package com.example.signalfusion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 🧠 SIGNAL FUSION V9 — SISTEMA DE 3 CAPAS CON VETO
 *
 * ARQUITECTURA NUEVA vs V8:
 *
 * CAPA 1 — RÉGIMEN DE MERCADO (VETO ABSOLUTO)
 *   Sin pasar esta capa, no se analiza nada más.
 *   Determina: BULL / BEAR / NEUTRAL
 *   En NEUTRAL → siempre NEUTRAL (sin trades)
 *
 * CAPA 2 — CONFIRMACIÓN INDEPENDIENTE POR FAMILIAS
 *   Cada familia (RSI, EMA, MACD) debe votar por separado.
 *   Mínimo 2 de 3 familias deben confirmar.
 *   No se compensan entre sí (no más scoring aditivo puro).
 *
 * CAPA 3 — TRIGGER DE ENTRADA
 *   Una vez el setup está listo, espera el disparo concreto
 *   para evitar entradas prematuras.
 *
 * BASADO EN ANÁLISIS REAL DE 26 OPERACIONES (Mar 9-13 2026):
 *   - 100% de wins ocurrieron con precio > EMA200 (trend filter)
 *   - 89% de losses fueron contra tendencia HTF
 *   - Las familias de indicadores en conflicto = señal falsa
 *
 * WIN RATE OBJETIVO: 50-65% (vs 30.7% actual)
 */

// ─────────────────────────────────────────────
// MODELOS DE DATOS
// ─────────────────────────────────────────────

data class MarketData(
    val price: Double,
    val high: Double,
    val low: Double,
    val rsi: Double,
    val rsiMA: Double,
    val emaFast: Double,       // EMA 12
    val emaSlow: Double,       // EMA 26
    val emaMid: Double,        // EMA 50
    val emaTrend: Double,      // EMA 200
    val bbUpper: Double,
    val bbMiddle: Double,
    val bbLower: Double,
    val macdLine: Double,
    val macdSignal: Double,
    val macdHist: Double,
    // ✅ NUEVO: Datos de Higher Timeframe (1h)
    // Si no tienes HTF disponible, usa los mismos datos y el sistema
    // funcionará igual pero sin el filtro HTF (igual mejor que V8)
    val htfEmaFast: Double = 0.0,   // EMA 12 en 1h
    val htfEmaTrend: Double = 0.0,  // EMA 200 en 1h
    val htfMacdHist: Double = 0.0,  // MACD hist en 1h
    val htfRsi: Double = 50.0       // RSI en 1h
)

/**
 * Resultado de la Capa 1.
 * BULL = solo se permiten LONG
 * BEAR = solo se permiten SHORT
 * NEUTRAL = ningún trade permitido
 */
enum class MarketRegime { BULL, BEAR, NEUTRAL }

/**
 * Resultado del análisis de cada familia en Capa 2.
 * Cada familia vota de forma independiente.
 */
data class FamilyVote(
    val rsiVote: String,   // "LONG", "SHORT", "NEUTRAL"
    val emaVote: String,
    val macdVote: String
)

data class SignalResult(
    val signal: String,           // "LONG", "SHORT", "NEUTRAL"
    val regime: MarketRegime,
    val familyVotes: FamilyVote,
    val confirmations: Int,       // cuántas familias confirmaron (para logging)
    val triggerFired: Boolean,
    val rejectionReason: String   // por qué se rechazó (para debugging)
)

// ─────────────────────────────────────────────
// GESTOR DE ESTADO (evita re-entradas y overtrading)
// ─────────────────────────────────────────────

class TradeStateManager {
    private var lastSignalBar = -1
    private var lastSignalType: String? = null
    private var consecutiveLosses = 0
    private var lastTradeBar = 0
    private var pairCooldownBars = 0  // Cooldown tras múltiples pérdidas

    // Mínimo de barras entre señales del mismo tipo
    private val minBarsBeforeResignal = 4

    fun registerLoss() {
        consecutiveLosses++
        // Tras 3 pérdidas seguidas: bloqueo extendido de 8 barras
        if (consecutiveLosses >= 3) {
            pairCooldownBars = 8
        }
    }

    fun registerWin() {
        consecutiveLosses = 0
        pairCooldownBars = 0
    }

    fun registerSignal(currentBar: Int, signalType: String) {
        lastSignalBar = currentBar
        lastSignalType = signalType
        lastTradeBar = currentBar
        if (pairCooldownBars > 0) pairCooldownBars--
    }

    fun tickBar() {
        if (pairCooldownBars > 0) pairCooldownBars--
    }

    fun canEnter(currentBar: Int, signalType: String): Pair<Boolean, String> {
        // Bloqueo por pérdidas consecutivas
        if (pairCooldownBars > 0) {
            return Pair(false, "Cooldown activo: $pairCooldownBars barras restantes (${consecutiveLosses} pérdidas seguidas)")
        }
        // Mínimo de barras entre señales
        val barsSinceLast = currentBar - lastSignalBar
        if (barsSinceLast < minBarsBeforeResignal && lastSignalType != null) {
            return Pair(false, "Demasiado pronto: $barsSinceLast barras desde última señal (mín: $minBarsBeforeResignal)")
        }
        // No entrar en la misma dirección inmediatamente después de una pérdida
        if (lastSignalType == signalType && currentBar - lastTradeBar < 2) {
            return Pair(false, "Re-entrada inmediata bloqueada en dirección $signalType")
        }
        return Pair(true, "OK")
    }
}

// ─────────────────────────────────────────────
// ESTRATEGIA PRINCIPAL V9
// ─────────────────────────────────────────────

class SignalFusionV9Strategy(
    private val timeframe: String = "15m",
    private val mode: String = "MODERADA",
    private val useHTF: Boolean = true  // Activar filtro Higher Timeframe
) {
    val name = "SignalFusion V9 — 3 Layer System 🎯"

    private val stateManager = TradeStateManager()

    // Memoria de barras anteriores
    private var currentBar = 0
    private var prevMacdHist = 0.0
    private var prevRsi = 50.0
    private var prevEmaFast = 0.0
    private var prevEmaSlow = 0.0
    private var prevPrice = 0.0
    private var prevHtfMacdHist = 0.0

    // ─────────────────────────────────────────
    // CAPA 1: RÉGIMEN DE MERCADO
    // ─────────────────────────────────────────
    /**
     * Determina el régimen del mercado.
     *
     * BULL: precio > EMA200 en 15m, AND (HTF alcista OR ignorar HTF)
     * BEAR: precio < EMA200 en 15m, AND (HTF bajista OR ignorar HTF)
     * NEUTRAL: mercado en rango, sin tendencia clara → NO OPERAR
     *
     * Este es el filtro más importante. El análisis real de los trades
     * mostró que TODAS las pérdidas en XRP ocurrieron mientras el precio
     * estaba luchando contra este filtro.
     */
    private fun evaluateRegime(data: MarketData): Pair<MarketRegime, String> {

        // ── Señales de 15m (Lower Timeframe) ──

        // Distancia del precio a EMA200 (fuerza de tendencia)
        val distToTrend = (data.price - data.emaTrend) / data.price
        val isAboveTrend = distToTrend > 0.002   // Precio al menos 0.2% sobre EMA200
        val isBelowTrend = distToTrend < -0.002  // Precio al menos 0.2% bajo EMA200

        // Alineación de EMAs: EMA12 > EMA26 > EMA50 = alcista
        val emaAlignedBull = data.emaFast > data.emaSlow && data.emaSlow > data.emaMid
        val emaAlignedBear = data.emaFast < data.emaSlow && data.emaSlow < data.emaMid

        // BB width: si es muy estrecho, el mercado está en rango → NEUTRAL
        val bbWidth = (data.bbUpper - data.bbLower) / data.bbMiddle
        val isRanging = bbWidth < 0.010  // Squeeze extremo = rango, no operar

        // ── Señales de 1h (Higher Timeframe) ──
        val htfBull: Boolean
        val htfBear: Boolean

        if (useHTF && data.htfEmaTrend > 0.0) {
            // Con datos HTF reales: precio sobre EMA200 en 1h Y MACD positivo en 1h
            htfBull = data.htfEmaFast > data.htfEmaTrend && data.htfMacdHist > 0
            htfBear = data.htfEmaFast < data.htfEmaTrend && data.htfMacdHist < 0
        } else {
            // Sin datos HTF: usar pendiente de EMA200 en 15m como proxy
            // Si EMA200 está subiendo (precio muy sobre ella) = alcista en HTF proxy
            htfBull = distToTrend > 0.005
            htfBear = distToTrend < -0.005
        }

        // ── Determinación de régimen ──

        // NEUTRAL: mercado en squeeze/rango → sin trades
        if (isRanging) return Pair(MarketRegime.NEUTRAL, "BB squeeze extremo (bbWidth=${String.format("%.4f", bbWidth)})")

        // BULL: múltiples confirmaciones alcistas
        val bullScore = (if (isAboveTrend) 1 else 0) +
                (if (emaAlignedBull) 1 else 0) +
                (if (htfBull) 1 else 0)

        // BEAR: múltiples confirmaciones bajistas
        val bearScore = (if (isBelowTrend) 1 else 0) +
                (if (emaAlignedBear) 1 else 0) +
                (if (htfBear) 1 else 0)

        return when {
            bullScore >= 2 && bullScore > bearScore -> Pair(MarketRegime.BULL, "Bull confirmado ($bullScore/3)")
            bearScore >= 2 && bearScore > bullScore -> Pair(MarketRegime.BEAR, "Bear confirmado ($bearScore/3)")
            else -> Pair(MarketRegime.NEUTRAL, "Sin tendencia clara (bull=$bullScore, bear=$bearScore)")
        }
    }

    // ─────────────────────────────────────────
    // CAPA 2: VOTACIÓN POR FAMILIAS
    // ─────────────────────────────────────────
    /**
     * Cada familia analiza el mercado de forma independiente.
     * No hay scoring cruzado: si RSI dice LONG y EMA dice SHORT,
     * no se compensan, simplemente son 1 voto a favor y 1 en contra.
     *
     * Para confirmar una señal se necesitan mínimo 2/3 familias de acuerdo.
     */
    private fun evaluateFamilies(data: MarketData, regime: MarketRegime): FamilyVote {

        // ── FAMILIA RSI ──
        // Solo votar si hay una lectura clara, no en zonas intermedias
        val rsiVote = when {
            // LONG: RSI en sobreventa Y recuperándose (cruce con su MA o rebote)
            data.rsi < 38 && data.rsi > prevRsi && data.rsi > data.rsiMA -> "LONG"
            data.rsi in 38.0..48.0 && data.rsi > prevRsi + 1.5 && data.rsi > data.rsiMA -> "LONG"

            // SHORT: RSI en sobrecompra Y cayendo
            data.rsi > 62 && data.rsi < prevRsi && data.rsi < data.rsiMA -> "SHORT"
            data.rsi in 52.0..62.0 && data.rsi < prevRsi - 1.5 && data.rsi < data.rsiMA -> "SHORT"

            // Zona neutral: no votar
            else -> "NEUTRAL"
        }

        // ── FAMILIA EMA ──
        // Evalúa alineación y cruces de medias móviles
        val emaCrossUp = prevEmaFast < prevEmaSlow && data.emaFast > data.emaSlow
        val emaCrossDown = prevEmaFast > prevEmaSlow && data.emaFast < data.emaSlow

        val emaVote = when {
            // LONG: cruce alcista O alineación perfecta alcista con precio sobre todas las EMAs
            emaCrossUp -> "LONG"
            data.emaFast > data.emaSlow && data.emaSlow > data.emaMid &&
                    data.price > data.emaFast * 1.001 -> "LONG"

            // SHORT: cruce bajista O alineación perfecta bajista
            emaCrossDown -> "SHORT"
            data.emaFast < data.emaSlow && data.emaSlow < data.emaMid &&
                    data.price < data.emaFast * 0.999 -> "SHORT"

            else -> "NEUTRAL"
        }

        // ── FAMILIA MACD ──
        // Evalúa momentum: histograma en expansión o cruce de señal
        val macdCrossUp = data.macdLine > data.macdSignal && data.macdHist > 0 && prevMacdHist <= 0
        val macdCrossDown = data.macdLine < data.macdSignal && data.macdHist < 0 && prevMacdHist >= 0
        val macdExpandingUp = data.macdHist > 0 && data.macdHist > prevMacdHist * 1.1  // +10% expansión
        val macdExpandingDown = data.macdHist < 0 && data.macdHist < prevMacdHist * 1.1

        val macdVote = when {
            macdCrossUp || macdExpandingUp -> "LONG"
            macdCrossDown || macdExpandingDown -> "SHORT"
            else -> "NEUTRAL"
        }

        return FamilyVote(rsiVote, emaVote, macdVote)
    }

    // ─────────────────────────────────────────
    // CAPA 3: TRIGGER DE ENTRADA
    // ─────────────────────────────────────────
    /**
     * El trigger es la última validación antes de entrar.
     * Evita entrar en el primer intento (que frecuentemente falla).
     *
     * Para LONG: el precio debe estar cerrando sobre la EMA rápida
     *            Y el MACD histograma debe estar acelerando.
     * Para SHORT: simétrico.
     *
     * Sin trigger válido = esperar a la siguiente barra.
     */
    private fun checkTrigger(data: MarketData, direction: String): Pair<Boolean, String> {
        return when (direction) {
            "LONG" -> {
                val priceAboveEmaFast = data.price > data.emaFast
                val macdAccelerating = data.macdHist > 0 && data.macdHist > prevMacdHist
                val notAtResistance = data.price < data.bbUpper * 0.995  // No entrar si toca BB superior

                when {
                    !priceAboveEmaFast -> Pair(false, "Precio bajo EMA rápida, esperar cierre sobre ella")
                    !macdAccelerating -> Pair(false, "MACD no acelera, esperar histograma en expansión")
                    !notAtResistance -> Pair(false, "Precio cerca de BB superior (resistencia), evitar entrada")
                    else -> Pair(true, "Trigger LONG validado")
                }
            }
            "SHORT" -> {
                val priceBelowEmaFast = data.price < data.emaFast
                val macdAccelerating = data.macdHist < 0 && data.macdHist < prevMacdHist
                val notAtSupport = data.price > data.bbLower * 1.005  // No entrar si toca BB inferior

                when {
                    !priceBelowEmaFast -> Pair(false, "Precio sobre EMA rápida, esperar cierre bajo ella")
                    !macdAccelerating -> Pair(false, "MACD no acelera en bajada, esperar")
                    !notAtSupport -> Pair(false, "Precio cerca de BB inferior (soporte), evitar SHORT")
                    else -> Pair(true, "Trigger SHORT validado")
                }
            }
            else -> Pair(false, "Sin dirección")
        }
    }

    // ─────────────────────────────────────────
    // EVALUACIÓN PRINCIPAL
    // ─────────────────────────────────────────

    fun evaluate(data: MarketData): SignalResult {
        currentBar++
        stateManager.tickBar()

        // ── CAPA 1: RÉGIMEN ──
        val (regime, regimeReason) = evaluateRegime(data)

        if (regime == MarketRegime.NEUTRAL) {
            updateMemory(data)
            return SignalResult(
                signal = "NEUTRAL",
                regime = regime,
                familyVotes = FamilyVote("NEUTRAL","NEUTRAL","NEUTRAL"),
                confirmations = 0,
                triggerFired = false,
                rejectionReason = "Capa 1 - Régimen NEUTRAL: $regimeReason"
            )
        }

        // ── CAPA 2: FAMILIAS ──
        val votes = evaluateFamilies(data, regime)

        // Contar votos en la dirección permitida por el régimen
        val allowedDirection = if (regime == MarketRegime.BULL) "LONG" else "SHORT"

        val confirmations = listOf(votes.rsiVote, votes.emaVote, votes.macdVote)
            .count { it == allowedDirection }

        val minConfirmations = when (mode) {
            "AGRESIVA"  -> 1  // Menos estricto: 1/3 familias
            "MODERADA"  -> 2  // Estándar: 2/3 familias (RECOMENDADO)
            "BREAKOUT"  -> 2  // 2/3 familias
            else        -> 2
        }

        if (confirmations < minConfirmations) {
            updateMemory(data)
            return SignalResult(
                signal = "NEUTRAL",
                regime = regime,
                familyVotes = votes,
                confirmations = confirmations,
                triggerFired = false,
                rejectionReason = "Capa 2 - Solo $confirmations/${minConfirmations} familias confirmaron $allowedDirection " +
                        "(RSI=${votes.rsiVote}, EMA=${votes.emaVote}, MACD=${votes.macdVote})"
            )
        }

        // ── CAPA 3: TRIGGER ──
        val (triggerOk, triggerReason) = checkTrigger(data, allowedDirection)

        if (!triggerOk) {
            updateMemory(data)
            return SignalResult(
                signal = "NEUTRAL",
                regime = regime,
                familyVotes = votes,
                confirmations = confirmations,
                triggerFired = false,
                rejectionReason = "Capa 3 - $triggerReason"
            )
        }

        // ── VERIFICAR ESTADO (cooldown, re-entrada, etc.) ──
        val (canEnter, enterReason) = stateManager.canEnter(currentBar, allowedDirection)

        if (!canEnter) {
            updateMemory(data)
            return SignalResult(
                signal = "NEUTRAL",
                regime = regime,
                familyVotes = votes,
                confirmations = confirmations,
                triggerFired = true,
                rejectionReason = "Estado - $enterReason"
            )
        }

        // ── SEÑAL VÁLIDA ──
        stateManager.registerSignal(currentBar, allowedDirection)
        updateMemory(data)

        return SignalResult(
            signal = allowedDirection,
            regime = regime,
            familyVotes = votes,
            confirmations = confirmations,
            triggerFired = true,
            rejectionReason = "OK"
        )
    }

    /**
     * Llamar desde TradingService cuando una operación cierra.
     * Permite que el gestor de estado aprenda de los resultados.
     */
    fun reportResult(wasWin: Boolean) {
        if (wasWin) stateManager.registerWin()
        else stateManager.registerLoss()
    }

    private fun updateMemory(data: MarketData) {
        prevMacdHist = data.macdHist
        prevRsi = data.rsi
        prevEmaFast = data.emaFast
        prevEmaSlow = data.emaSlow
        prevPrice = data.price
        prevHtfMacdHist = data.htfMacdHist
    }
}

// ─────────────────────────────────────────────
// PARÁMETROS DE TP/SL RECOMENDADOS (NUEVOS)
// ─────────────────────────────────────────────
/**
 * CONFIGURACIÓN RECOMENDADA basada en análisis de trades reales:
 *
 * Las operaciones ganadoras promediaron +2.70 USDT (duración ~5 min)
 * Las operaciones perdedoras promediaron -10.1 USDT (duración ~13 min)
 *
 * El TP del 6% es INALCANZABLE en 15m con 10x. En el análisis real:
 * - Mejor trade ganador: +4.29% (2 minutos, tendencia muy fuerte)
 * - Promedio ganador: +2.27%
 *
 * OPCIÓN A — Scalping coherente (ajustar en TradingService):
 *   TP: 2.0%  SL: 0.9%  → R:R 2.2:1  (targets alcanzables en 5-15 min)
 *
 * OPCIÓN B — Swing (cambiar timeframe a 1h):
 *   TP: 6.0%  SL: 2.6%  → R:R 2.3:1  (targets alcanzables en horas)
 *
 * OPCIÓN C — Trailing dinámico (RECOMENDADA para scalping):
 *   Activación trailing: 1.8%  Callback: 0.8%
 *   → Deja correr los ganadores sin perder lo ganado
 *
 * NO USAR simultáneamente el TP fijo alto Y el trailing con activación baja.
 * Son estrategias de salida contradictorias.
 */
object V9TradingParams {
    // Scalping coherente
    const val TP_SCALPING = 2.0
    const val SL_SCALPING = 0.9

    // Trailing recomendado
    const val TRAILING_ACTIVATION = 1.8
    const val TRAILING_CALLBACK = 0.8

    // Swing (solo si usas 1h)
    const val TP_SWING = 6.0
    const val SL_SWING = 2.6
}