# SignalFusion Pro — Bot de Trading Algorítmico para Android

> Bot de futuros para Bitget construido nativamente en Kotlin para Android. Opera en modo DEMO y REAL sobre contratos USDT-M perpetuos con gestión de riesgo automática.

---

## Estado actual — V9.4 Swing 1H

| Parámetro | Valor |
|-----------|-------|
| Versión | V9.4 |
| Timeframe | 1h |
| Estrategia | MODERADA (2/3 familias) |
| Apalancamiento | 5x |
| Take Profit | 4.0% |
| Stop Loss | 2.0% |
| Ratio R:R | 2.0:1 |
| Trailing activación | 3.0% |
| Trailing callback | 1.0% |
| Cooldown base | 60 min (escala con pérdidas) |
| Horario | 24h — filtro por ATR mínimo 0.3% |
| Monedas | BTC por defecto (ETH opcional) |

---

## Arquitectura — Sistema de 3 Capas de Veto

El motor V9.4 evalúa cada señal pasando por tres capas de validación independientes. Si una capa veta, no se analiza la siguiente.

### Capa 1 — Régimen de Mercado (veto absoluto)

Determina si el mercado está en tendencia o en rango lateral. Si está en rango, no se opera.

- **BULL**: precio > EMA200, EMAs alineadas al alza, HTF proxy alcista → solo LONGs
- **BEAR**: precio < EMA200, EMAs alineadas a la baja, HTF proxy bajista → solo SHORTs
- **NEUTRAL**: BB squeeze o sin tendencia clara → ningún trade

### Capa 2 — Votación por Familias

Tres familias de indicadores votan de forma independiente. En modo MODERADA se necesitan 2/3 para confirmar.

- **RSI (14)**: vota cuando hay momentum claro vela-a-vela comparado con RSI MA (7)
- **EMA**: vota por cruces EMA12/EMA26 o alineación perfecta con precio
- **MACD**: vota por cruces de señal o expansión del histograma entre velas consecutivas

### Capa 3 — Trigger de Entrada

Última validación antes de abrir posición:
- Precio debe estar al lado correcto de la EMA rápida
- MACD histograma debe confirmar la dirección

---

## Gestión de Riesgo

**Tamaño de posición** calculado como porcentaje del balance disponible:

| Balance | Margen por trade |
|---------|-----------------|
| < $100 | 5% del balance |
| $100 – $2,000 | 4% del balance |
| > $2,000 | Configurable (default 5%) |

**Cooldown escalado tras pérdidas:**

| Pérdidas consecutivas | Cooldown |
|-----------------------|---------|
| 0 (win o arranque) | 60 min |
| 1 pérdida | 90 min |
| 2 pérdidas | 120 min |
| 3 pérdidas | 150 min |

El cooldown **persiste entre reinicios** del servicio — se guarda en SharedPreferences y se restaura automáticamente al arrancar.

---

## Indicadores calculados nativamente

Todos los indicadores se calculan sobre las velas descargadas via API, sin librerías externas:

- **RSI (14)** + **RSI MA (7)** — momentum y cruces
- **MACD (12, 26, 9)** — línea, señal e histograma
- **Bollinger Bands (20, 2.0)** — squeeze y régimen lateral
- **EMA 12** (rápida), **EMA 26** (lenta), **EMA 50** (soporte dinámico), **EMA 200** (filtro de tendencia)
- **ATR (14)** — volatilidad mínima exigida: 0.3% en 1h

---



---

## Configuración inicial

### 1. API Key de Bitget

Crea una API Key en Bitget con permisos de **Lectura** y **Trade (Futuros)**. No habilites permisos de retiro. Introduce las credenciales en la pestaña Ajustes de la app.

### 2. Parámetros en la app

- Timeframe → `1h`
- Estrategia → `MODERADA`
- Monedas → `BTC` (desactiva ETH y XRP hasta validar resultados)
- Modo → `DEMO` hasta acumular 30+ trades con win rate positivo

### 3. Optimización Android obligatoria

El bot corre como `ForegroundService`. Para que Android no lo mate en segundo plano:

```
Ajustes → Aplicaciones → SignalFusion → Batería → Sin restricciones
```

Habilita notificaciones de la app para recibir alertas de trades en tiempo real.

---

## Modo DEMO vs REAL

El bot detecta automáticamente el modo según `BitgetConfig`. En DEMO los símbolos tienen prefijo `S` (por ejemplo `SBTCSUSDT`). El `productType` cambia de `USDT-FUTURES` a `SUSDT-FUTURES`.

Para cambiar de modo edita `BitgetConfig.kt` o usa el selector en Ajustes si está implementado en tu versión de la UI.

---

## Cómo interpretar el Logcat

Filtra por `BOT_DEBUG` en Android Studio para ver solo los logs del bot:

| Mensaje | Significado |
|---------|-------------|
| `✅ MOTOR V9.4 DEMO` | Bot iniciado correctamente |
| `✅ SBTCSUSDT: 200 velas cargadas` | Historial descargado, bot calibrado |
| `🚫 Capa 1 - NEUTRAL: BB squeeze` | Mercado en rango, esperando tendencia |
| `🚫 Capa 2 - 1/2 (SHORT) RSI=NEUTRAL EMA=SHORT MACD=NEUTRAL` | Falta una familia para confirmar — normal |
| `✅✅✅ SEÑAL V9.4: SHORT en BTCUSDT` | Señal válida, abriendo posición |
| `🛡️ Trailing: Max 3.20% → 2.10%` | Trailing stop activado |
| `🏁 CERRADO | PnL: +1.85%` | Trade cerrado con resultado |
| `⏳ Cooldown 58min` | En espera entre trades |

---

## Historial de versiones

### V9.4 — Swing 1H (actual)
- Cambio de filosofía: de scalping 5m a swing 1h
- Leverage reducido de 10x a 5x
- TP 4.0% / SL 2.0% — ratio R:R 2:1 matemáticamente viable
- Modo MODERADA por defecto — exige 2/3 familias
- Cooldown base aumentado a 60 minutos
- Opera 24h con ATR como único filtro de mercado dormido
- Solo BTC por defecto — menor spread en DEMO

### V9.3 — Filtered MACD
- Eliminado `macdStaticLong/Short` que causaba entradas en cualquier condición
- Cooldown persistente en SharedPreferences
- Fix coma locale español en `lastPrice.toDoubleOrNull()`
- Cooldown escalado dinámicamente tras pérdidas consecutivas

### V9.2 — Candle-Sync Fix
- Fix crítico: `evaluate()` recibe `isNewCandle: Boolean`
- `updateMemory()` solo se llama cuando llega una vela nueva real
- Eliminados falsos cruces causados por evaluaciones cada 3 segundos con el mismo historial

### V9.1 — RSI Zones Fix
- Zonas RSI corregidas para régimen BULL: votaba LONG solo con RSI < 38, que nunca ocurría en tendencia alcista
- BB squeeze relajado de 0.010 a 0.005
- SL subido de 0.9% a 1.5% para sobrevivir spread del modo DEMO

### V9.0 — Sistema de 3 Capas
- Arquitectura nueva: Régimen → Familias → Trigger
- Basado en análisis real de 26 operaciones (marzo 2026)
- `TradeStateManager` para cooldown por barras entre señales

### V8 y anteriores
- Motor de scoring aditivo con sesgos fijos
- Reemplazado por sistema de veto independiente por familias

---

## Disclaimer

El trading de futuros de criptomonedas conlleva riesgo elevado de pérdida de capital. SignalFusion Pro es una herramienta algorítmica con fines educativos y experimentales. Opera exclusivamente bajo tu propia responsabilidad. Valida siempre en modo DEMO antes de usar capital real.

---

*Desarrollado por [@jsoto-06](https://github.com/jsoto-06)*
