# 🚀 SignalFusion Pro v5.1 Ultimate (Sniper Edition)

> **El Motor Cuantitativo Institucional para Futuros de Bitget (USDT-M)**

SignalFusion Pro ha evolucionado. La versión 5.1 Ultimate deja atrás los condicionales básicos (`if/else`) para introducir un **Sistema de Scoring Cuantitativo** avanzado. Diseñado nativamente para Android (Kotlin), este bot ejecuta análisis técnico pesado en segundo plano, ponderando múltiples indicadores para tomar decisiones de trading con precisión quirúrgica (Modo Francotirador) y protegiendo el capital ante caídas extremas del mercado.

---

## 💎 Arquitectura y Características Exclusivas

### 🧠 Motor de Inferencia (Scoring System)
El corazón de la v5.1. El bot ya no dispara por un simple cruce de RSI. Ahora evalúa el mercado asignando un **puntaje ponderado** basado en la temporalidad (Timeframe). 
* Suma puntos por rebotes en Bandas de Bollinger, cruces de MACD, medias móviles (EMAs) y RSI. 
* Solo ejecuta la orden si la puntuación acumulada supera un **Umbral Dinámico** matemático.

### 🛡️ ATR Dinámico y Escudo de Capital (NUEVO v5.1)
Se acabó el riesgo descontrolado. El algoritmo calcula el **Average True Range (ATR)** en tiempo real, pero ahora utiliza una lógica asimétrica para proteger la cuenta:
* **Stop Loss Blindado:** Fuerza matemáticamente a cortar las pérdidas rápido, usando `minOf` para garantizar que nunca se supere el riesgo máximo configurado por el usuario.
* **Take Profit Expansivo:** Usa `maxOf` para dejar correr las ganancias si el ATR detecta alta volatilidad a favor.
* **Trailing Stop Conservador:** Solo asegura ganancias cuando el trade ha superado el +2.2% de ROE, evitando cierres prematuros de operaciones ganadoras.

### 🛑 Filtro Anticaídas (EMA 200 Trend Filter)
Protección vital contra desplomes del mercado. El motor penaliza severamente (`-2.0 puntos`) cualquier intento de abrir una posición en largo (LONG) si el precio se encuentra por debajo de la Media Móvil Lenta, evitando el error fatal de "atrapar cuchillos cayendo".

### ⚖️ Cooldown Institucional (Anti-Overtrading)
Implementación de un bloqueo estricto de **15 minutos** entre operaciones. Obliga al bot a ignorar el "ruido" del mercado y esperar a que las velas se consoliden, reduciendo drásticamente el pago de comisiones (Fees) al exchange y aumentando el Win Rate.

### 🔋 Persistencia Inmortal (WakeLock Kernel)
Implementación de bloqueos de energía a nivel de kernel (`PARTIAL_WAKE_LOCK`). Mientras otros bots de Android son asesinados por el ahorro de batería, SignalFusion Pro mantiene el hilo de red y la CPU despiertos 24/7.

---

## 📊 El Arsenal Técnico (Indicadores Nativos)
SignalFusion calcula todos estos indicadores matemáticamente desde las velas crudas obtenidas vía API:
* **RSI (14)** y **RSI MA (7)** para cruces de momentum.
* **MACD (12, 26, 9)** (Línea principal, Señal e Histograma).
* **Bandas de Bollinger (20, 2.0)** para detección de desviaciones estándar.
* **Escuadrón de EMAs:** $EMA_{12}$ (Rápida), $EMA_{26}$ (Lenta), $EMA_{50}$ (Soporte dinámico) y $EMA_{200}$ (Filtro de Tendencia).

---

## 🛠️ Manual de Vuelo (Configuración y Despegue)

### 1. Conexión de API (Bitget)
Crea una API Key en Bitget con permisos exclusivos de **Lectura** y **Trade (Futuros)**. *No habilites permisos de retiro.* Introduce la API Key, Secret Key y Passphrase en Ajustes.

### 2. Gestión de Riesgo Inteligente (Smart Bypass)
* **Límite de Seguridad UI:** La interfaz bloquea el riesgo visual al 10% para evitar errores de tipeo catastróficos.
* **Bypass Automático (Cuentas Pequeñas):** Si tu saldo es inferior a $50, el motor interno forzará un riesgo temporal mayor (35%) única y exclusivamente para superar el requisito legal de Bitget de tamaño mínimo de contrato (0.001 BTC). Una vez la cuenta crezca, el bot volverá a usar el 10% estándar.
* **Cálculo Real:** El bot descuenta de forma precisa el *Taker Fee* (0.06% x2) y el *Slippage* (0.15%) para mostrar un PnL 100% neto y real.

### 3. Selector de Estrategias
* 🟢 **MODERADA (Francotirador):** Configuración óptima para 15m. Exige una alineación perfecta de indicadores (MACD + Bollinger + Tendencia) antes de disparar. Menos operaciones, altísima precisión (Ratio 2:1 real).
* 🔴 **AGRESIVA (Alta Frecuencia):** Umbral de puntaje más bajo. Diseñada para scalping rápido en temporalidades bajas (1m, 5m).

### 4. ⚠️ OBLIGATORIO: Optimización de Android
Para que el bot no se apague en segundo plano:
`Ajustes de Android > Aplicaciones > SignalFusion Pro > Batería > Seleccionar "Sin Restricciones" (o "No Optimizar")`.

---

## 📜 Registro de Misiones (Changelog)

### **v5.1 Ultimate - Sniper Patch (Actual)**
* **[CRÍTICO]** Corrección de la lógica de ATR: El Stop Loss ahora respeta el límite de usuario de forma estricta (`minOf`).
* **[NUEVO]** Añadido Cooldown de 15m para erradicar el Overtrading y el sangrado por comisiones.
* **[NUEVO]** Filtro de Tendencia (EMA Lenta) añadido al Scoring para bloquear LONGs en mercados bajistas.
* **[NUEVO]** Bias bajista (Short Bias) integrado en el algoritmo para adaptarse a las condiciones actuales del mercado.
* **[NUEVO]** *Smart Bypass* para cuentas menores a $50 (permite operar BTC cumpliendo el mínimo de 0.001 BTC de Bitget sin fallos de API).
* **[REFACTOR]** Trailing Stop optimizado: Solo se activa al alcanzar +2.2% de ROE para evitar cierres prematuros de operaciones ganadoras.
* **[FIX]** Cálculo de PnL corregido para reflejar Taker Fees y Slippage exactos.

### **v5.0 Ultimate - El Salto Cuantitativo**
* Eliminación de condicionales `if/else` rígidos. Nuevo motor `SignalFusionUltimateStrategy.kt`.
* Sistema de Scoring adaptativo por Timeframe (1m, 5m, 15m).
* Cerebros múltiples simultáneos (Multi-Threading) por activo.

---

## ⚠️ Disclaimer Financiero
**El trading de futuros de criptomonedas conlleva un riesgo extremo de pérdida total del capital.** SignalFusion Pro es una herramienta de automatización algorítmica de código abierto con fines educativos y experimentales. El autor de este repositorio no se hace responsable de liquidaciones, errores de API, o pérdidas financieras derivadas del uso de este software. **Opere bajo su propia responsabilidad.**

---
*Desarrollado y mantenido con ☕ y ❤️ por IG: jonathansoto06*
