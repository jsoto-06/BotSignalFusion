package com.example.signalfusion

data class TradeData(
    val symbol: String,
    val side: String,
    val pnl: Double,
    val timestamp: Long
)