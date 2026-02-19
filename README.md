# 🚀 SignalFusion Pro v5.0 Ultimate

> **El Motor Cuantitativo Institucional para Futuros de Bitget (USDT-M)**

SignalFusion Pro ha evolucionado. La versión 5.0 Ultimate deja atrás los condicionales básicos (`if/else`) para introducir un **Sistema de Scoring Cuantitativo** avanzado. Diseñado nativamente para Android (Kotlin), este bot de alta frecuencia ejecuta análisis técnico pesado en segundo plano, ponderando múltiples indicadores para tomar decisiones de trading con precisión quirúrgica sin que el sistema operativo interrumpa su ejecución.

---

## 💎 Arquitectura y Características Exclusivas

### 🧠 Motor de Inferencia (Scoring System)
El corazón de la v5.0. El bot ya no dispara por un simple cruce de RSI. Ahora evalúa el mercado asignando un **puntaje ponderado** basado en la temporalidad (Timeframe). 
* Suma puntos por rebotes en Bandas de Bollinger, cruces de MACD, medias móviles (EMAs) y RSI. 
* Solo ejecuta la orden si la puntuación acumulada supera un **Umbral Dinámico** matemático.

### 🛡️ ATR Dinámico para Stop Loss y Take Profit
Se acabó operar a ciegas. El algoritmo calcula el **Average True Range (ATR)** en tiempo real y expande o contrae dinámicamente el Stop Loss y Take Profit adaptándose a la volatilidad real de la criptomoneda, usando los porcentajes del usuario solo como un "seguro de vida" adicional.

### 🐙 Cerebros Múltiples Simultáneos (Multi-Threading)
SignalFusion asigna una instancia de inteligencia artificial independiente (`SignalFusionUltimateStrategy`) para cada activo activo (BTC, ETH, SOL, XRP). El análisis de Bitcoin jamás interferirá con los cálculos de Ethereum.

### ⚖️ Gestor de Doble Confirmación (Anti-Whipsaw)
Protección contra la esquizofrenia del mercado. El `DoubleConfirmationManager` bloquea cambios bruscos de dirección (ej. cambiar de LONG a SHORT en la misma vela por mechas engañosas), exigiendo un periodo de confirmación y enfriamiento.

### 🔋 Persistencia Inmortal (WakeLock Kernel)
Implementación de bloqueos de energía a nivel de kernel (`PARTIAL_WAKE_LOCK`). Mientras otros bots de Android son asesinados por el ahorro de batería al apagar la pantalla, SignalFusion Pro mantiene el hilo de red y la CPU despiertos 24/7.

---

## 📊 El Arsenal Técnico (Indicadores Nativos)
SignalFusion V5.0 calcula todos estos indicadores matemáticamente desde las velas crudas obtenidas vía API:
* **RSI (14)** y **RSI MA (7)** para cruces de momentum.
* **MACD (12, 26, 9)** (Línea principal, Señal e Histograma).
* **Bandas de Bollinger (20, 2.0)** para detección de desviaciones estándar.
* **Escuadrón de EMAs:** $EMA_{12}$ (Rápida), $EMA_{26}$ (Lenta), $EMA_{50}$ (Soporte dinámico) y $EMA_{200}$ (Filtro de Tendencia Mayor).
* **High/Low Virtuales (0.5%):** Simulador de mechas para detección de volatilidad precisa en arquitecturas de solo-cierre.

---

## 🛠️ Manual de Vuelo (Configuración y Despegue)

### 1. Conexión de API (Bitget)
Crea una API Key en Bitget con permisos exclusivos de **Lectura** y **Trade (Futuros)**. *No habilites permisos de retiro.* Introduce la API Key, Secret Key y Passphrase en la pestaña de Ajustes.

### 2. Gestión de Riesgo (Risk Management)
* **Riesgo por Operación:** Por seguridad institucional, la v5.0 **limita el riesgo máximo al 10%** del capital total disponible. (Recomendado: 3% a 5%).
* **Cálculo de Posición:** El bot utiliza la fórmula exacta de nocional `(Margen * Leverage) / Precio` garantizando que las órdenes superen el mínimo de $5 exigido por el exchange.
* **Apalancamiento:** Asegúrate de que el apalancamiento seleccionado en la App coincida exactamente con el de tu cuenta de Bitget.

### 3. Selector de Estrategias
* 🟢 **MODERADA (Segura):** Exige un alto puntaje de confirmación. Actúa como francotirador. Entra en zonas de agotamiento extremo con confluencia de MACD y Bollinger.
* 🔴 **AGRESIVA (Alta Frecuencia):** Umbral de puntaje más bajo. Diseñada para scalping rápido en temporalidades bajas (1m, 5m).
* 🔵 **BREAKOUT (Tendencia):** Caza de impulsos y rupturas de rango a favor de la $EMA_{200}$.

### 4. ⚠️ OBLIGATORIO: Optimización de Android
Para que el bot no se apague en segundo plano, debes configurar tu teléfono:
`Ajustes de Android > Aplicaciones > SignalFusion Pro > Batería > Seleccionar "Sin Restricciones" (o "No Optimizar")`.

---

## 📜 Registro de Misiones (Changelog)

### **v5.0 Ultimate - El Salto Cuantitativo**
* **[REFACTOR]** Eliminación de condicionales `if/else` rígidos. Nuevo motor `SignalFusionUltimateStrategy.kt`.
* **[NUEVO]** Sistema de Scoring adaptativo por Timeframe (1m, 5m, 15m, 30m, 1h).
* **[NUEVO]** Integración algorítmica de MACD, Bollinger Bands, y 4x EMAs.
* **[NUEVO]** Stop Loss y Take Profit dinámicos basados en la volatilidad (ATR).
* **[NUEVO]** Interfaz de Ajustes limpiada. Añadido escudo de validación que bloquea riesgos > 10%.
* **[NUEVO]** Historial PnL en vivo conectado al endpoint `history-position` de Bitget (Lectura de últimos 7 días).
* **[FIX]** Fórmula de tamaño de orden de apalancamiento cruzado corregida para cumplir con los mínimos nocionales del exchange.

---

## ⚠️ Disclaimer Financiero
**El trading de futuros de criptomonedas conlleva un riesgo extremo de pérdida total del capital.** SignalFusion Pro es una herramienta de automatización algorítmica de código abierto con fines educativos y experimentales. El autor de este repositorio no se hace responsable de liquidaciones, errores de API, o pérdidas financieras derivadas del uso de este software. Utilice cuentas demo (Testnet/Paper Trading) antes de operar con dinero real. **Opere bajo su propia responsabilidad.**

---
*Desarrollado y mantenido con ☕ y ❤️ por IG: jonathansoto06*
