# 🚀 SignalFusion: Autonomous Bitget Trading Bot

![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg?style=flat&logo=kotlin)
![Platform](https://img.shields.io/badge/Platform-Android-green.svg?style=flat&logo=android)
![API](https://img.shields.io/badge/Exchange-Bitget_V2-blue.svg?style=flat&logo=bitget)
![License](https://img.shields.io/badge/License-MIT-orange.svg?style=flat)

**SignalFusion** is a high-frequency, algorithmic trading bot designed for Android devices. It operates as a persistent Foreground Service, scanning the cryptocurrency market 24/7 to execute trades on **Bitget USDT-M Futures** autonomously.

Unlike simple signal notifyers, SignalFusion **executes orders directly** on your exchange account with built-in risk management, dynamic strategies, and multi-asset scanning.

---

## 📱 Features

### 🧠 Intelligent Core
* **Multi-Asset Scanner:** Simultaneously monitors **BTC, ETH, SOL, and XRP** in a round-robin cycle.
* **Background Operation:** Runs as a robust Android Service (`ForegroundService`), ensuring execution even when the screen is off.
* **Real-Time Analysis:** Fetches candlestick data (1m/5m/15m) and calculates indicators (RSI, EMA, ATR, Volume) locally.

### 🛡️ Risk Management (The "Shield")
* **Hard Stop Loss (SL) & Take Profit (TP):** Pre-calculated before entry.
* **Trailing Stop:** Secures profits when the price moves in your favor.
* **Circuit Breaker:** Automatically shuts down the bot if daily losses exceed a defined threshold (e.g., 10%).
* **Cooldown System:** Prevents over-trading by enforcing pauses between operations.
* **Anti-Streak Protection:** Pauses trading for 1 hour after 5 consecutive losses.

### ⚙️ Strategies
1.  **🛡️ Moderate (Trend Following):**
    * Requires strict trend confirmation (EMA9 > EMA21).
    * Enters only on deep pullbacks (RSI < 35 for Longs).
    * Volume filter to avoid fake-outs.
2.  **⚡ Aggressive (Scalping):**
    * Faster entries based on RSI divergence (RSI < 40).
    * Ignores strict volume filters for high-frequency opportunities.
    * Ideal for ranging markets.
3.  **🚀 Breakout (Volatility):**
    * Detects volatility expansion using ATR and Bollinger Band simulations.
    * Enters when price aggressively breaks resistance/support with momentum.

---

## 📸 Screenshots

| Dashboard | Settings & Config | Active Trade |
|:---:|:---:|:---:|
| *(Place your screenshot here)* | *(Place your screenshot here)* | *(Place your screenshot here)* |
| *Real-time Scanner* | *API & Strategy Setup* | *Live PnL Tracking* |

---

## 🛠️ Installation

### Prerequisites
* Android Studio Iguana (or newer).
* Android Device with Android 8.0 (Oreo) or higher.
* **Bitget Account** with Futures enabled.

### Setup
1.  **Clone the repository:**
    ```bash
    git clone [https://github.com/jsoto-06/BitgetBot-SignalFusion.git](https://github.com/your-username/BitgetBot-SignalFusion.git)
    ```
2.  **Open in Android Studio** and let Gradle sync.
3.  **Permissions:** Ensure your device allows the app to run in the background (Settings > Apps > SignalFusion > Battery > **Unrestricted**).
4.  **Build & Run** on your device.

---

## 🔑 Configuration

The bot is designed to be **Secure by Default**. API Keys are **NEVER** hardcoded.

1.  Open the App and go to **Settings (⚙️)**.
2.  **API Connection:**
    * Enter your **API Key**.
    * Enter your **Secret Key**.
    * Enter your **Passphrase** (Bitget Requirement).
3.  **Select Assets:** Toggle switches for BTC, ETH, SOL, XRP.
4.  **Risk Settings:**
    * Define Leverage (e.g., 5x, 10x).
    * Set Risk % per trade (e.g., 20% of balance).
5.  **Save & Start.**

> **Security Note:** All credentials are stored locally using Android's `SharedPreferences` in private mode. They are never sent to any third-party server, only directly to Bitget API endpoints.

---

## 🏗️ Architecture

The project follows a modular architecture:

* **`TradingService.kt`**: The brain. Handles the infinite loop, API calls, strategy logic, and order execution.
* **`BitgetUtils.kt`**: Handles HMAC-SHA256 signing and HTTP requests to Bitget V2 API.
* **`Indicadores.kt`**: Mathematical utility class for calculating RSI, EMA, and ATR from raw candle data.
* **`SettingsFragment.kt`**: UI controller for managing user preferences.

---

## ⚠️ Disclaimer

**USE AT YOUR OWN RISK.**

This software is for educational purposes only. Cryptocurrency trading involves a high level of risk and may not be suitable for all investors. You could lose some or all of your initial investment. The developers of SignalFusion are not responsible for any financial losses incurred while using this bot.

Always test with a small amount (or Demo account) before deploying significant capital.

---

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.

---

Made with ❤️ and ☕ by [IG:jonathansoto06]
