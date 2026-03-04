package com.example.signalfusion

import android.content.Context

/**
 * 🎯 SISTEMA DE TRADING DEMO/REAL
 * 
 * Bitget permite operar en:
 * - REAL: productType=USDT-FUTURES (dinero real)
 * - DEMO: productType=SUSDT-FUTURES (dinero simulado)
 * 
 * Este objeto gestiona la configuración global del bot.
 */
object BitgetConfig {
    
    // Estado global (se carga desde SharedPreferences)
    private var isDemoMode = true  // Por defecto DEMO para seguridad
    
    /**
     * Obtiene el productType correcto según el modo
     */
    fun getProductType(): String {
        return if (isDemoMode) "SUSDT-FUTURES" else "USDT-FUTURES"
    }
    
    /**
     * Obtiene el marginCoin correcto según el modo
     */
    fun getMarginCoin(): String {
        return if (isDemoMode) "SUSDT" else "USDT"
    }
    
    /**
     * Verifica si está en modo DEMO
     */
    fun isDemo(): Boolean = isDemoMode
    
    /**
     * Establece el modo de trading
     */
    fun setDemoMode(demo: Boolean) {
        isDemoMode = demo
    }
    
    /**
     * Carga configuración desde SharedPreferences
     */
    fun loadFromPreferences(context: Context) {
        val prefs = context.getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        isDemoMode = prefs.getBoolean("DEMO_MODE", true)  // Default: DEMO
    }
    
    /**
     * Guarda configuración en SharedPreferences
     */
    fun saveToPreferences(context: Context, demoMode: Boolean) {
        val prefs = context.getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("DEMO_MODE", demoMode).apply()
        isDemoMode = demoMode
    }
    
    /**
     * Obtiene indicador visual para UI
     */
    fun getModeIndicator(): String {
        return if (isDemoMode) "📊 DEMO" else "💰 REAL"
    }
    
    /**
     * Obtiene color para UI
     */
    fun getModeColor(): String {
        return if (isDemoMode) "#FFB74D" else "#00E676"  // Naranja = DEMO, Verde = REAL
    }
    
    /**
     * Mensaje de advertencia antes de cambiar a REAL
     */
    fun getWarningMessage(): String {
        return if (isDemoMode) {
            "⚠️ ADVERTENCIA: Vas a operar con DINERO REAL. " +
            "Asegúrate de que el bot funciona bien en DEMO primero."
        } else {
            "Cambiando a modo DEMO (simulado). " +
            "Podrás probar estrategias sin riesgo."
        }
    }
}
