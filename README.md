# 🚀 SignalFusion Pro v7.2 (Institutional Sniper Edition)

> **El Motor Cuantitativo Institucional para Futuros de Bitget (USDT-M)**

SignalFusion Pro ha evolucionado a su forma definitiva. La versión 7.2 deja atrás los condicionales básicos y los sesgos fijos para introducir un **Sistema de Scoring Cuantitativo** avanzado y una **Gestión de Riesgo Matemática Inquebrantable**. Diseñado nativamente para Android (Kotlin), este bot ejecuta análisis técnico pesado en segundo plano, ponderando múltiples indicadores para tomar decisiones de trading con precisión quirúrgica (Modo Francotirador) y protegiendo el capital de forma estricta.

---

## 💎 Arquitectura y Características Exclusivas

### 🧠 Motor de Inferencia (Scoring System Dinámico)
El corazón de la v7.2. El bot ya no dispara por un simple cruce de RSI ni tiene un sesgo predefinido. Ahora evalúa el mercado asignando un **puntaje ponderado** basado en la temporalidad (Timeframe). 
* Suma puntos por rebotes en Bandas de Bollinger, cruces de MACD, alineación de medias móviles (EMAs) y RSI. 
* Adapta su sesgo (Bullish/Bearish) dependiendo de si el precio está por encima o por debajo de la EMA de 200 períodos.

### 🛡️ Gestión de Riesgo Institucional (NUEVO v7.2)
Se acabó el riesgo descontrolado o los errores de configuración. El algoritmo ahora tiene un "seguro de vida" interno codificado para garantizar rentabilidad a largo plazo:
* **Ratio Riesgo/Beneficio 2.4 a 1:** El bot impone matemáticamente un Stop Loss del `2.6%` y un Take Profit del `6.0%`. Arriesgas 1 para ganar más de 2.
* **Bypass de Margen Dinámico:** Diseñado para levantar cuentas pequeñas. Dependiendo de tu saldo, inyecta un porcentaje u otro para cumplir los mínimos del exchange sin sobreapalancarte (Ej: Si tienes <$20, inyecta máx $4.00 reales).
* **Filtro Anti-Ruido (ATR Estricto):** Exige una volatilidad mínima del `1.2%`. Si el mercado está lateral o aburrido, el bot se bloquea automáticamente para no quemar comisiones.

### ⚡ Trailing Stop Súper Agresivo
Para cuentas pequeñas, vale más pájaro en mano. El bot activa un escudo dinámico en cuanto la operación alcanza un **+1.5%** de ganancia. Si el mercado se gira repentinamente y retrocede un **0.6%**, cierra la operación en positivo, evitando que un trade ganador se convierta en perdedor.

### ⚖️ Cooldown Adaptativo (Anti-Revenge Trading)
Implementación de un bloqueo inteligente entre operaciones. 
* **Base:** 15 minutos tras una operación exitosa.
* **Castigo por pérdida:** Si la operación toca Stop Loss, el bot añade +10 minutos extra al temporizador (1ª pérdida = 25 min, 2ª = 35 min, etc.). Erradica el overtrading y el sangrado por comisiones.

### 📊 Dashboard Analítico y Win Rate Tracking
Integración total con la API v2 de Bitget (`history-orders`). El bot descarga las últimas operaciones reales y calcula en vivo tu **Win Rate (%)** y tu **PnL Neto de las últimas 24 horas**, mostrándolo en la interfaz junto con un historial de posiciones 🟢🔴.

### 🔋 Persistencia Inmortal (WakeLock Kernel)
Implementación de bloqueos de energía a nivel de kernel (`PARTIAL_WAKE_LOCK`). Mientras otros bots de Android son asesinados por el ahorro de batería, SignalFusion Pro mantiene el hilo de red y la CPU despiertos 24/7.

---

## 📊 El Arsenal Técnico (Indicadores Nativos)
SignalFusion calcula todos estos indicadores matemáticamente desde las velas crudas obtenidas vía API:
* **RSI (14)** y **RSI MA (7)** para cruces de momentum.
* **MACD (12, 26, 9)** (Línea principal, Señal e Histograma).
* **Bandas de Bollinger (20, 2.0)** para detección de desviaciones estándar y *Squeeze* (compresión del mercado).
* **Escuadrón de EMAs:** $EMA_{12}$ (Rápida), $EMA_{26}$ (Lenta), $EMA_{50}$ (Soporte dinámico) y $EMA_{200}$ (Filtro de Tendencia).
* **ATR (14)**: Rango verdadero promedio porcentual.

---

## 🛠️ Manual de Vuelo (Configuración y Despegue)

### 1. Conexión de API (Bitget)
Crea una API Key en Bitget con permisos exclusivos de **Lectura** y **Trade (Futuros)**. *No habilites permisos de retiro.* Introduce la API Key, Secret Key y Passphrase en Ajustes.

### 2. Configuración de la App
* **Filtro de Monedas:** Marca solo los activos que desees operar (Ej: `ETH`, `SOL`, `XRP`). *Nota: Si tu cuenta es menor a $50, desactiva `BTC` debido al alto tamaño mínimo de contrato.*
* **Estrategia:** 🟢 **MODERADA (Francotirador):** Configuración óptima y recomendada. Menos operaciones, altísima precisión.
* *Nota de Seguridad:* El motor v7.2 ignorará los valores de "SL" y "TP" que pongas en la interfaz para forzar su matemática segura interna (SL 2.6% / TP 6.0%).

### 3. ⚠️ OBLIGATORIO: Optimización de Android
Para que el bot no se apague en segundo plano y mantenga el servicio activo (`ForegroundService` tipo `dataSync`):
1. `Ajustes de Android > Aplicaciones > SignalFusion Pro > Batería > Seleccionar "Sin Restricciones"`.
2. Habilita las notificaciones de la app para ver los trades en tiempo real.

---

## 📜 Registro de Misiones (Changelog)

### **v7.2 SF - The Institutional Patch (Actual)**
* **[CRÍTICO]** Corrección matemática del cálculo de margen (`sizeAmount = margenDeseado / precio`). Elimina el bug de exposición x100.
* **[CRÍTICO]** Sincronización del Historial unificada con la API v2. Win Rate y PnL Diario 100% precisos y renderizados en la UI.
* **[NUEVO]** Bypass de margen dinámico estructurado en 4 niveles para proteger y levantar cuentas desde $10 USD.
* **[NUEVO]** Trailing Stop agresivo (Activación 1.5% / Callback 0.6%) para asegurar ganancias rápidas.
* **[NUEVO]** Filtro ATR endurecido al 1.2% para descartar "ruido" y falsos rompimientos.
* **[NUEVO]** Sistema de Cooldown penalizador por pérdidas consecutivas (15min base + 10min por Loss).
* **[FIX]** Ajuste total del `AndroidManifest.xml` para compatibilidad nativa con servicios en segundo plano de Android 14.

### **v5.1 Ultimate - Sniper Patch**
* Corrección de la lógica de ATR con límites estrictos (`minOf`).
* Filtro de Tendencia (EMA Lenta) añadido al Scoring.

### **v5.0 Ultimate - El Salto Cuantitativo**
* Eliminación de condicionales `if/else` rígidos. Nuevo motor `SignalFusionUltimateStrategy.kt`.
* Sistema de Scoring adaptativo.

---

## ⚠️ Disclaimer Financiero
**El trading de futuros de criptomonedas conlleva un riesgo extremo de pérdida total del capital.** SignalFusion Pro es una herramienta de automatización algorítmica de código abierto con fines educativos y experimentales. El autor de este repositorio no se hace responsable de liquidaciones, errores de API, fallos de red o pérdidas financieras derivadas del uso de este software. **Opere bajo su propia y absoluta responsabilidad.**

---
*Desarrollado y mantenido con ☕ y ❤️ por IG: jonathansoto06*
