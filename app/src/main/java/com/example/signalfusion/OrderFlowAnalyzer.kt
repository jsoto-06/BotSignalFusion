package com.example.signalfusion

import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 📊 ORDER FLOW ANALYZER
 *
 * Calcula el flujo real de órdenes usando trades individuales de la API de Bitget.
 * Endpoint: /api/v2/mix/market/fills
 *
 * Métricas calculadas:
 *
 * DELTA por período = volumen comprador - volumen vendedor
 *   Positivo → compradores dominan → mercado con presión alcista
 *   Negativo → vendedores dominan → mercado con presión bajista
 *
 * CVD (Cumulative Volume Delta) = suma acumulada del delta
 *   CVD subiendo con precio subiendo → tendencia alcista confirmada
 *   CVD bajando con precio bajando → tendencia bajista confirmada
 *   CVD SUBIENDO con precio BAJANDO → divergencia bajista falsa → NO SHORT
 *   CVD BAJANDO con precio SUBIENDO → divergencia alcista falsa → NO LONG
 *
 * BUY RATIO = volumen comprador / volumen total
 *   > 0.55 → dominancia compradora
 *   < 0.45 → dominancia vendedora
 *   0.45–0.55 → equilibrio → mercado sin dirección clara
 *
 * ABSORCIÓN = precio baja pero delta positivo
 *   Los compradores están absorbiendo las ventas → rebote probable
 *   El bot NO debe shortear en absorción
 */

data class OrderFlowData(
    val delta: Double,           // volumen comprador - volumen vendedor (período actual)
    val cvd: Double,             // CVD acumulado (últimas N velas)
    val cvdTrend: String,        // "UP", "DOWN", "FLAT"
    val buyRatio: Double,        // % volumen comprador (0.0 a 1.0)
    val hasAbsorption: Boolean,  // precio baja pero compradores dominan
    val hasDivergence: Boolean,  // precio y CVD en direcciones opuestas
    val dominance: String        // "BUYERS", "SELLERS", "NEUTRAL"
)

class OrderFlowAnalyzer {

    // Historial de deltas por símbolo (últimas 20 velas de 1h)
    private val deltaHistory = mutableMapOf<String, ArrayDeque<Double>>()
    private val maxHistory = 20

    /**
     * Parsea la respuesta del endpoint /api/v2/mix/market/fills
     * y calcula las métricas de order flow.
     *
     * @param tradesJson respuesta JSON del endpoint de trades
     * @param currentPrice precio actual del símbolo
     * @param prevPrice precio de la vela anterior (para detectar dirección)
     * @param symbol símbolo para gestionar el historial
     */
    fun analyze(
        tradesJson: String,
        currentPrice: Double,
        prevPrice: Double,
        symbol: String
    ): OrderFlowData {
        return try {
            val json = JSONObject(tradesJson)
            if (json.optString("code") != "00000") {
                return neutralOrderFlow()
            }

            val data = json.optJSONArray("data") ?: return neutralOrderFlow()
            if (data.length() == 0) return neutralOrderFlow()

            var buyVolume  = 0.0
            var sellVolume = 0.0

            for (i in 0 until data.length()) {
                val trade = data.getJSONObject(i)
                // side: "buy" = taker compra (presión alcista)
                //       "sell" = taker vende (presión bajista)
                val side   = trade.optString("side", "")
                val size   = trade.optString("size", "0").toDoubleOrNull() ?: 0.0
                val price  = trade.optString("price", "0").toDoubleOrNull() ?: 0.0
                val volume = size * price   // en USDT

                when (side) {
                    "buy"  -> buyVolume  += volume
                    "sell" -> sellVolume += volume
                }
            }

            val totalVolume = buyVolume + sellVolume
            if (totalVolume == 0.0) return neutralOrderFlow()

            val delta    = buyVolume - sellVolume
            val buyRatio = buyVolume / totalVolume

            // Actualizar historial de deltas
            val history = deltaHistory.getOrPut(symbol) { ArrayDeque() }
            history.addLast(delta)
            if (history.size > maxHistory) history.removeFirst()

            // CVD = suma de los últimos N deltas
            val cvd = history.sum()

            // Tendencia del CVD: comparar primera mitad vs segunda mitad del historial
            val cvdTrend = if (history.size >= 4) {
                val mid   = history.size / 2
                val first = history.take(mid).sum()
                val last  = history.drop(mid).sum()
                when {
                    last > first * 1.1  -> "UP"
                    last < first * 0.9  -> "DOWN"
                    else                -> "FLAT"
                }
            } else "FLAT"

            // Dirección del precio
            val priceDown = currentPrice < prevPrice * 0.9995  // precio bajó al menos 0.05%
            val priceUp   = currentPrice > prevPrice * 1.0005  // precio subió al menos 0.05%

            // Absorción: precio baja pero compradores dominan
            val hasAbsorption = priceDown && buyRatio > 0.52

            // Divergencia: CVD y precio van en direcciones opuestas
            val hasDivergence = (priceDown && cvdTrend == "UP") ||
                    (priceUp  && cvdTrend == "DOWN")

            // Dominancia general
            val dominance = when {
                buyRatio > 0.55  -> "BUYERS"
                buyRatio < 0.45  -> "SELLERS"
                else             -> "NEUTRAL"
            }

            OrderFlowData(
                delta         = delta,
                cvd           = cvd,
                cvdTrend      = cvdTrend,
                buyRatio      = buyRatio,
                hasAbsorption = hasAbsorption,
                hasDivergence = hasDivergence,
                dominance     = dominance
            )
        } catch (e: Exception) {
            android.util.Log.e("BOT_DEBUG", "OrderFlow error: ${e.message}")
            neutralOrderFlow()
        }
    }

    /**
     * Evalúa si el Order Flow confirma o bloquea una señal de trading.
     *
     * @param signal "LONG" o "SHORT"
     * @param of datos de order flow calculados
     * @return Pair(confirmado, razón)
     */
    fun confirmSignal(signal: String, of: OrderFlowData): Pair<Boolean, String> {
        return when (signal) {
            "SHORT" -> {
                when {
                    // Bloquear SHORT si hay absorción masiva (compradores absorbiendo la caída)
                    of.hasAbsorption ->
                        Pair(false, "Absorción detectada — compradores dominan (buyRatio=${"%.2f".format(of.buyRatio)})")

                    // Bloquear SHORT si CVD diverge al alza (precio baja pero volumen comprador sube)
                    of.hasDivergence && of.cvdTrend == "UP" ->
                        Pair(false, "Divergencia alcista en CVD — caída sin respaldo vendedor")

                    // Bloquear SHORT si compradores dominan claramente
                    of.dominance == "BUYERS" && of.cvdTrend != "DOWN" ->
                        Pair(false, "Compradores dominan (${(of.buyRatio * 100).toInt()}% buy) sin CVD bajista")

                    // SHORT confirmado: vendedores dominan o CVD bajista
                    of.dominance == "SELLERS" || of.cvdTrend == "DOWN" ->
                        Pair(true, "Order Flow SHORT ✅ (sellers=${(( 1 - of.buyRatio) * 100).toInt()}% CVD=${of.cvdTrend})")

                    // Neutro: dejar pasar (no bloquear, no confirmar activamente)
                    else ->
                        Pair(true, "Order Flow neutro — sin divergencia clara")
                }
            }
            "LONG" -> {
                when {
                    // Bloquear LONG si hay distribución (vendedores absorbiendo la subida)
                    !of.hasAbsorption && of.dominance == "SELLERS" && of.cvdTrend == "DOWN" ->
                        Pair(false, "Distribución detectada — vendedores dominan la subida")

                    // Bloquear LONG si CVD diverge a la baja
                    of.hasDivergence && of.cvdTrend == "DOWN" ->
                        Pair(false, "Divergencia bajista en CVD — subida sin respaldo comprador")

                    // LONG confirmado: compradores dominan
                    of.dominance == "BUYERS" || of.cvdTrend == "UP" ->
                        Pair(true, "Order Flow LONG ✅ (buyers=${(of.buyRatio * 100).toInt()}% CVD=${of.cvdTrend})")

                    else ->
                        Pair(true, "Order Flow neutro — sin divergencia clara")
                }
            }
            else -> Pair(false, "Señal desconocida")
        }
    }

    private fun neutralOrderFlow() = OrderFlowData(
        delta         = 0.0,
        cvd           = 0.0,
        cvdTrend      = "FLAT",
        buyRatio      = 0.5,
        hasAbsorption = false,
        hasDivergence = false,
        dominance     = "NEUTRAL"
    )

    fun resetSymbol(symbol: String) {
        deltaHistory.remove(symbol)
    }
}