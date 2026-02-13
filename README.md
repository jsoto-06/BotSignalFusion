# ⚡ SignalFusion Pro v3.2 - High Frequency Trading Bot (Android)

![Platform](https://img.shields.io/badge/PLATFORM-ANDROID-green) ![Language](https://img.shields.io/badge/KOTLIN-100%25-purple) ![Status](https://img.shields.io/badge/STATUS-OPERATIONAL-brightgreen) ![License](https://img.shields.io/badge/LICENSE-MIT-blue)

**SignalFusion Pro** es una terminal de trading algorítmico autónoma diseñada para operar en el mercado de futuros de criptomonedas (Bitget API). A diferencia de los bots en la nube, esta aplicación se ejecuta localmente en dispositivos Android utilizando servicios en primer plano para garantizar una latencia mínima y control total sobre los datos.

---

## 🔥 Características Principales (Core Features)

### 1. 🧠 Motor de Persistencia de Datos (Nuevo en v3.2)
El sistema cuenta con una capa de memoria persistente (`SharedPreferences`) que actúa como una "caja negra" del avión.
- **Tolerancia a Fallos:** Si la app se cierra, el teléfono se reinicia o el proceso muere, el bot **restaura** automáticamente el estado de la operación (Precio de entrada, Stop Loss, PnL Máximo).
- **Continuidad:** El balance y las estadísticas de Win Rate se preservan entre sesiones.

### 2. 🌍 Escáner Multi-Activo Inteligente
El bot no se limita a Bitcoin. Utiliza un sistema de **Rotación Secuencial** para monitorear múltiples pares:
- **Activos Soportados:** BTC/USDT, ETH/USDT, SOL/USDT, XRP/USDT.
- **Lógica de Bloqueo:** Escanea el mercado cada 2 segundos. Cuando detecta una oportunidad y abre una operación, el escáner se **bloquea** en ese activo para gestionar la salida con precisión de milisegundos, ignorando el resto del mercado hasta cerrar el trade.

### 3. 🛡️ Sistema de Protección "Circuit Breaker"
Inspirado en Wall Street, el bot incluye un mecanismo de seguridad pasiva:
- **Límite de Pérdida Diaria:** Si el capital total cae un **5%** (configurable) respecto al inicio de la sesión, el motor se **autodestruye** (detiene todos los servicios) para prevenir la liquidación de la cuenta.
- **Notificación de Emergencia:** Envía una alerta crítica al usuario informando del bloqueo.

### 4. 📊 Dashboard de Salud en Tiempo Real
Visualización instantánea del rendimiento del algoritmo:
- **Semáforo de Salud:** Indicadores visuales (🟢 🟡 🔴) basados en el rendimiento actual.
- **Métricas Vivas:** Cálculo en tiempo real del **ROI** (Retorno de Inversión) y **Win Rate** (Tasa de Acierto).

### 5. 📲 Notificaciones Push
Sistema de alertas mediante canales de notificación de Android:
- **Canal Silencioso:** Mantiene el servicio vivo en la barra de estado.
- **Canal de Alertas:** Vibración y sonido al abrir/cerrar operaciones (`OPEN LONG`, `TAKE PROFIT`, `STOP LOSS`).

---

## 📈 Estrategias Algorítmicas

El bot implementa tres lógicas de trading distintas, seleccionables por el usuario:

| Estrategia | Perfil de Riesgo | Indicadores Técnicos | Descripción |
| :--- | :---: | :--- | :--- |
| **MODERADA** | Bajo | EMA9, EMA21, RSI, Volatilidad | Busca cruces de medias móviles a favor de la tendencia. Solo opera si el RSI confirma la dirección. |
| **AGRESIVA** | Alto | RSI (Extremo), Scalping | **(Activa)** Busca reversiones rápidas en zonas de sobrecompra (>70) o sobreventa (<30). Usa Trailing Stop ajustado. |
| **BREAKOUT** | Medio | Volumen, Bandas de Precio | Detecta explosiones de volatilidad y entra en la dirección de la ruptura con confirmación de volumen. |

---

## 🛠️ Arquitectura Técnica

El proyecto sigue una arquitectura **MVVM simplificada** para Android:

* **Language:** Kotlin.
* **Concurrency:** Coroutines (Dispatchers.IO para red, Main para UI).
* **Networking:** OkHttp 4 (Conexiones REST API a Bitget).
* **JSON Parsing:** `org.json` nativo para máxima velocidad.
* **Background Processing:** Android Foreground Service con `START_STICKY` para resistencia al sistema operativo.

### Estructura de Archivos Clave
- `TradingService.kt`: El cerebro. Maneja el bucle infinito, lógica de trading y conexión API.
- `BotFragment.kt`: La interfaz de usuario. Recibe `Broadcasts` del servicio para actualizar gráficos y textos.
- `Indicadores.kt`: Librería matemática propia para cálculo de RSI, EMA y ATR sin dependencias externas.

---

## 🚀 Instalación y Configuración

1.  **Requisitos:**
    - Android Studio Hedgehog o superior.
    - Dispositivo con Android 8.0 (Oreo) o superior.
    - Conexión a Internet estable.

2.  **Configuración de API:**
    - Generar API Keys en Bitget (Permisos: *Futures Trading*).
    - Ingresar Keys en la pestaña **Ajustes** de la app.

3.  **Primer Uso:**
    - Seleccionar monedas a operar (Checkboxes).
    - Elegir Estrategia (Recomendada: *Agresiva* para pruebas).
    - Activar el interruptor **"Motor Automático"**.

---

## ⚠️ Disclaimer & Seguridad

Este software es un proyecto de desarrollo personal y educativo.
- **Las llaves API** se guardan localmente en el dispositivo (`SharedPreferences` en modo privado).
- El autor no se hace responsable de pérdidas financieras derivadas del uso de este software en cuentas reales.
- Se recomienda encarecidamente usar el modo **Paper Trading** (Simulación) antes de arriesgar capital real.

---

> **Desarrollado con 💻 y ☕ por by IG: @jonathansoto06**
---
Creado con ❤️ para la comunidad de trading. 
