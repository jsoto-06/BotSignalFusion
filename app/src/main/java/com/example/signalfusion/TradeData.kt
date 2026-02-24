package com.example.signalfusion

// ✅ Archivo único para el modelo de datos.
// Así evitas el error de "Redeclaration"
data class TradeData(
    val symbol: String,
    val side: String,
    val pnl: Double,
    val time: Long
)