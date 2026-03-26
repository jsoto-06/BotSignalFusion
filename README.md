# SignalFusion Pro — Bot de Trading Algorítmico para Android 🎯

> Bot de futuros para Bitget construido nativamente en Kotlin para Android. Opera en modo DEMO y REAL sobre contratos USDT-M perpetuos. Evolucionado a un sistema de "Francotirador" que combina Análisis de Múltiples Temporalidades (MTFA) y Flujo de Órdenes (Order Flow) en tiempo real.

---

## Estado actual — V9.6 MTFA Francotirador + Order Flow

| Parámetro | Valor |
|-----------|-------|
| Versión | V9.6 |
| Timeframes | **HTF:** 1H (Dirección) / **LTF:** 15m (Entrada) |
| Estrategia | MODERADA (2/3 familias en LTF) |
| Apalancamiento | 5x |
| Take Profit | 4.0% |
| Stop Loss | 2.0% |
| Ratio R:R | 2.0:1 |
| Trailing activación | 3.0% |
| Trailing callback | 1.0% |
| Cooldown base | 20 min tras win/apertura (escala severamente con pérdidas) |
| Monedas | BTC por defecto (Recomendado para Order Flow limpio) |

---

## Arquitectura — Sistema de 4 Capas (3D Trading)

El motor V9.6 evalúa el mercado en 3 dimensiones de tiempo distintas (Horas, Minutos, Segundos). Si una capa veta, la señal se descarta automáticamente.

### 🗺️ Capa 1 — Régimen HTF (1 Hora)
Actúa como la brújula del bot. Lee exclusivamente los datos macro para determinar la tendencia principal y bloquear operaciones en contra de la marea.
- **BULL**: Precio > EMA200 (1H), EMAs alineadas al alza, MACD (1H) alcista → solo LONGs.
- **BEAR**: Precio < EMA200 (1H), EMAs alineadas a la baja, MACD (1H) bajista → solo SHORTs.
- **NEUTRAL**: BB squeeze en 1H o sin tendencia clara → Se bloquea cualquier trade.

### 🔬 Capa 2 — Votación por Familias LTF (15 Minutos)
Actúa como la lupa. Busca el "timing" perfecto de entrada a favor de la tendencia de 1H. Requiere que 2 de 3 familias confirmen:
- **RSI (14)**: Momentum vela-a-vela cruzando su Media Móvil.
- **EMA**: Cruces EMA12/EMA26 o alineación perfecta en 15m.
- **MACD**: Cruces de señal o expansión de histograma (>6%) en 15m.

### 🎯 Capa 3 — Trigger LTF (15 Minutos)
Última validación técnica antes del Order Flow:
- El precio debe estar del lado correcto de la EMA rápida (15m).
- El MACD histograma (15m) debe acompañar la dirección.

### ⚡ Capa 4 — Order Flow (Tiempo Real)
El guardián definitivo. Se conecta al endpoint `/api/v2/mix/market/fills` para leer los últimos 500 trades reales cruzados en el exchange (Tickers).
- **Evita Absorciones**: Bloquea SHORTs si el precio cae pero el *Buy Ratio* y el Delta muestran compras masivas.
- **Divergencia CVD**: Bloquea entradas si el precio se mueve sin el respaldo del *Cumulative Volume Delta*.
- **Confirmación**: Solo permite disparar si el Smart Money está atacando en la misma dirección que las capas 1 y 2.

---

## Gestión de Riesgo

**Tamaño de posición** calculado como porcentaje del balance disponible (con protección de capital):

| Balance | Margen por trade |
|---------|-----------------|
| < $100 | 5% del balance |
| < $2,000 | 4% del balance |
| > $2,000 | Configurable (default 10% / 5%) |

**Cooldown escalado dinámico:**

| Evento | Cooldown |
|--------|---------|
| Tras Win / Apertura | 20 minutos |
| 1 Pérdida | 90 min (60 + 30) |
| 2 Pérdidas consecutivas | 120 min |
| 3 Pérdidas consecutivas | 150 min |

El cooldown **persiste entre reinicios** del dispositivo mediante `SharedPreferences`.

---

## Indicadores calculados nativamente

Todos los indicadores se calculan en memoria RAM sin librerías externas para máxima velocidad:

- **Indicadores de Precio (Dual: 15m y 1H)**: RSI, MACD, Bollinger Bands, EMA (12, 26, 50, 200), ATR.
- **Métricas Order Flow (Real-Time)**:
  - `Delta` = Volumen Compras - Volumen Ventas
  - `CVD (Cumulative Volume Delta)` = Suma de deltas recientes
  - `Buy Ratio %` = Porcentaje de dominancia compradora
  - `Absorption` = Divergencia micro entre precio y agresividad de mercado.

---

## Configuración inicial

### 1. API Key de Bitget
Crea una API Key en Bitget con permisos de **Lectura** y **Trade (Futuros)**. Introduce las credenciales en la pestaña Ajustes.

### 2. Optimización Android obligatoria
El bot corre como `ForegroundService` y mantiene conexiones WebSocket/HTTP activas. Para que Android (Doze Mode) no lo mate en segundo plano:

## Cómo interpretar el Logcat

Filtra por `BOT_DEBUG` en Android Studio para entender la mente del "Francotirador":

| Mensaje de Log | Significado |
|----------------|-------------|
| `🗺️ HTF: 1H (dirección) \| 🔬 LTF: 15m (entrada) \| ⚡ OF: tiempo real` | Arquitectura MTFA inicializada correctamente. |
| `📡 Velas 15m: SBTCSUSDT` <br> `📡 Velas 1H: SBTCSUSDT` | Descargando y separando memorias HTF y LTF. |
| `📊 [BTCUSDT] OF: BUYERS CVD=UP buy=84% abs=true` | Order Flow detecta compras masivas y absorción bajista. |
| `🚫 Capa 1 HTF - NEUTRAL: Sin tendencia` | La marea general (1H) no está clara. Botón de disparo bloqueado. |
| `🚫 Capa 2 LTF - 0/2 (LONG) RSI=NEUTRAL` | Tendencia de 1H clara, pero sin momentum en 15m para entrar. |
| `✅✅✅ SEÑAL V9.6 MTFA: SHORT en BTCUSDT` | Alineación perfecta: 1H bajista + 15m confirmando + OF atacando. |

---

## Historial de versiones

### V9.6 — MTFA Francotirador (Actual)
- Implementación de Análisis de Múltiples Temporalidades (MTFA).
- Carga dual asíncrona de historiales de velas (`ltfPreciosMap` 15m y `htfPreciosMap` 1H).
- Capa 1 ahora usa exclusivamente la temporalidad de 1H para blindar la dirección.

### V9.5 — Order Flow Layer
- Introducción de la Capa 4 de lectura de Cinta (Tape Reading).
- Parser en tiempo real del endpoint de `fills`.
- Algoritmo matemático para Delta, CVD, Divergencias y Detección de Absorción institucional.

### V9.4 — Swing Trading
- Cambio de filosofía: paso de scalping (ruido) a Swing Trading (tendencia).
- Apalancamiento reducido de 10x a 5x para minimizar impacto de fees.
- Ajuste de R:R matemático a 2.0:1 (TP 4.0% / SL 2.0%).

### V9.3 y anteriores
- Corrección de bugs de Locale (parseo de comas).
- Persistencia de cooldown en disco para sobrevivir a reinicios de Android.
- Sistema fundacional de 3 Capas de veto.

---

## Disclaimer

El trading de futuros de criptomonedas conlleva riesgo elevado de pérdida de capital. **SignalFusion Pro** es una herramienta algorítmica con fines educativos y de investigación tecnológica. Opera exclusivamente bajo tu propia responsabilidad. Valida siempre el Order Flow en modo DEMO antes de exponer capital real al mercado.

---
*Desarrollado por [@jsoto-06](https://github.com/jsoto-06)*
