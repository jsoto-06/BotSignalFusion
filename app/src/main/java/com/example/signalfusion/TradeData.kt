package com.example.signalfusion

data class TradeData(
    val symbol: String,
    val side: String, // "LONG" o "SHORT"
    val pnl: Double,
    val time: Long
)