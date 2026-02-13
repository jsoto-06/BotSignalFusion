# <p align="center">⚡ SignalFusion Pro v3.4</p>

<p align="center">
  <img src="https://img.shields.io/badge/Version-3.4_Stable-00FFA3?style=for-the-badge&logo=android" />
  <img src="https://img.shields.io/badge/Language-Kotlin_1.9-7F52FF?style=for-the-badge&logo=kotlin" />
  <img src="https://img.shields.io/badge/Optimization-S24_Ultra_%2F_S21-FFD700?style=for-the-badge&logo=samsung" />
</p>

<p align="center">
  <b>Terminal de trading algorítmico de alto rendimiento para Android.</b><br>
  <i>Diseñada para la ejecución profesional en el mercado de futuros (Bitget API).</i>
</p>

---

## 🆕 Novedades de la Versión 3.4 

### 📊 Inteligencia Visual y Gráficos
* **Curva de Crecimiento en Vivo:** Integración de `MPAndroidChart` para visualizar el balance en tiempo real. Observa tu progreso trade a trade con una línea de tendencia dinámica.
* **Historial Profesional:** Pestaña de historial rediseñada con tarjetas visuales. Identifica instantáneamente ganancias (**neón verde**) y pérdidas (**neón rojo**) con detalles de PnL y timestamp.

### 🛡️ Gestión de Riesgo de Precisión (User-Controlled)
* **Riesgo por Operación (%):** Ahora el usuario tiene el control total. Configura qué porcentaje del capital total (2%, 5%, 10%, etc.) se arriesga en cada entrada.
* **Botón de Pánico (Manual Close):** Control absoluto sobre el motor. Cierre instantáneo de cualquier posición abierta desde el Dashboard con un solo toque.
* **Escudo Anti-Rachas:** Protección inteligente que pausa el bot automáticamente durante 1 hora tras detectar 5 pérdidas consecutivas.

---

## 🔥 Características Principales

### 1. 🧠 Motor de Persistencia "Black Box"
Capa de memoria persistente que actúa como una caja negra. Si la app se cierra o el sistema reinicia el proceso, el bot **restaura automáticamente** el estado de la operación, el precio de entrada y el PnL máximo alcanzado.

### 2. 🌍 Escáner Multi-Activo Dinámico
Monitorización secuencial de pares críticos: **BTC, ETH, SOL y XRP**.
* **Lógica de Enfoque:** El escáner rota cada 2-4 segundos. Al abrir una posición, el motor se bloquea en ese activo para una gestión de salida de ultra-latencia.

### 3. 🛡️ Circuit Breaker (Seguridad Militar)
Si el capital total cae por debajo del límite diario configurado (ej. -10%), el bot ejecuta un **apagado de emergencia** de todos los servicios para proteger el balance principal.

### 4. ⚡ Tarjeta de Posición Activa
Dashboard dinámico durante operaciones activas:
- ⏱️ **Cronómetro** de duración del trade.
- 🟢 **PnL en tiempo real** con cambio de color dinámico.
- ↗️ **Tipo de entrada** (Long / Short).

---

## 📈 Estrategias Algorítmicas

| Estrategia | Perfil | Indicadores | Descripción |
| :--- | :--- | :--- | :--- |
| **AGRESIVA** | 🔴 Alto | RSI Extremo + Turbo | Scalping puro en 1m. Entradas rápidas en zonas de sobre-extensión. |
| **MODERADA** | 🟡 Medio | EMA 9/21 + RSI + ATR | Seguimiento de tendencia con confirmación de volatilidad. |
| **BREAKOUT** | 🔵 Medio | Volumen + EMA | Detección de rupturas de rango y explosiones de volumen. |

---

## 🛠️ Stack Tecnológico

```kotlin
// High-Performance Components
- Language: Kotlin 1.9
- Architecture: Android Foreground Service (START_STICKY)
- Optimization: Android 14 API Level 34 (S24/S21 Friendly)
- Charts: MPAndroidChart (Real-time Rendering)
- Networking: OkHttp 4 (REST API Bitget V2)
- Concurrency: Kotlin Coroutines (Dispatchers.IO)

> **Desarrollado con 💻 y ☕ por by IG: @jonathansoto06**
---
Creado con ❤️ para la comunidad de trading. 
