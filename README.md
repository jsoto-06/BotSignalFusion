# 🚀 SignalFusion Bot - Android Crypto Trading

![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg?style=flat&logo=kotlin)
![Platform](https://img.shields.io/badge/Platform-Android-green.svg?style=flat&logo=android)
![Exchange](https://img.shields.io/badge/Exchange-Bitget_Futures-cyan.svg?style=flat&logo=bitcoin)
![Status](https://img.shields.io/badge/Status-Active_v4.0-success.svg)

**SignalFusion** es un bot de trading algorítmico autónomo diseñado para operar en el mercado de futuros de **Bitget (USDT-M)**. Se ejecuta como un Servicio en Primer Plano (Foreground Service) en Android, permitiendo operaciones 24/7 directamente desde tu dispositivo móvil.

El sistema utiliza una arquitectura de **Fusión de Señales** basada en RSI, EMAs y Volumen para detectar entradas de alta probabilidad (Pullbacks) y gestiona la salida con un sistema dinámico de Trailing Stop.

---

## 📱 Características Principales

### 🧠 Motor de Trading Inteligente
* **Análisis en Tiempo Real:** Procesa velas de 1 minuto (`1m`) sincronizadas con la API V2 de Bitget.
* **Filtro de Tendencia:** Utiliza medias móviles exponenciales (EMA 9/21/50) para operar solo a favor de la tendencia.
* **Gestión de Ruido:** Sistema de "Timeframe Lock" para evitar señales falsas en micro-movimientos.

### 🛡️ Gestión de Riesgo Profesional (v4.0)
* **Cálculo de Posición Dinámico:** Calcula el tamaño de la orden basado en el margen exacto en USDT, evitando liquidaciones por sobre-apalancamiento.
* **Protección de Ganancias (Trailing Stop):** Asegura beneficios cuando el precio se mueve a favor (+1.5%) y cierra si retrocede (0.5%).
* **Circuit Breaker:** Apagado de emergencia automático si la cuenta pierde un 10% en una sesión.
* **Cálculo de Fees Neto:** El PnL mostrado descuenta automáticamente las comisiones de Bitget (~0.12%).

### ⚡ Interfaz "Inmortal"
* **Sincronización Instantánea:** La UI se actualiza milisegundos después de que el Exchange confirma una orden.
* **Persistencia:** Tarjeta de estado siempre visible, mostrando si el bot está "Escaneando" u "Operando".
* **Botón de Pánico:** Cierre manual de emergencia con prioridad alta.

---

## 📊 Estrategias Incluidas

El bot opera bajo dos lógicas principales configurables:

| Estrategia | Perfil | Indicadores de Entrada | Objetivo |
| :--- | :--- | :--- | :--- |
| **AGRESIVA** 🚀 | Alta Frecuencia | RSI < 45 (Long) / > 55 (Short) + EMA Cross | Capturar retrocesos rápidos en tendencias fuertes. |
| **MODERADA** 🛡️ | Conservadora | RSI < 35 (Long) / > 65 (Short) + EMA + Volumen | Entradas en zonas de sobrecompra/sobreventa extremas. |

---

## 🛠️ Stack Tecnológico

* **Lenguaje:** Kotlin
* **Arquitectura:** Android Service (Background processing) + BroadcastReceivers.
* **Red:** OkHttp3 (Peticiones REST API síncronas/asíncronas).
* **Parseo:** JSON nativo (org.json).
* **API:** Bitget V2 Mix API (Futuros).

---

## ⚙️ Configuración y Requisitos

Para compilar y ejecutar este proyecto necesitas:

1.  **Android Studio Iguana** (o superior).
2.  Una cuenta en **Bitget** con futuros habilitados.
3.  **API Keys** (Read + Trade) generadas en Bitget.

### Pasos de Instalación
1.  Clonar el repositorio.
2.  Abrir en Android Studio y sincronizar Gradle.
3.  Ejecutar en un dispositivo físico (recomendado) o emulador.
4.  Ir a la pestaña **Ajustes** e introducir:
    * API Key, Secret Key, Passphrase.
    * Riesgo (Recomendado: 30% - 50%).
    * Apalancamiento (Recomendado: 5x - 10x).

---

## ⚠️ Descargo de Responsabilidad (Disclaimer)

Este software es para fines educativos y experimentales. El trading de criptomonedas conlleva un alto nivel de riesgo y puede no ser adecuado para todos los inversores.

* **El autor no se hace responsable** de ninguna pérdida financiera derivada del uso de este bot.
* Utiliza siempre una gestión de riesgo adecuada y nunca operes con dinero que no puedas permitirte perder.
* El código almacena las claves localmente en el dispositivo. Asegúrate de proteger tu teléfono.

---

## 📝 Historial de Cambios (Changelog)

### v4.0 - Definitive Architect
* ✅ **FIX:** Solucionado error crítico de cálculo de tamaño de posición (Margin Mode).
* ✅ **FIX:** Corregido el timeframe del RSI (ahora respeta velas de 1m reales).
* ✅ **FEAT:** Implementado Trailing Stop y PnL Neto (después de fees).
* ✅ **UX:** Tarjeta de estado persistente y logs en tiempo real.

---

Hecho con ❤️☕ by [IG:jonathansoto06]
---

Made with ❤️ and ☕ 
