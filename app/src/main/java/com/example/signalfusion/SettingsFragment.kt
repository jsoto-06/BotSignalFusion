package com.example.signalfusion

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class SettingsFragment : Fragment() {

    // Vistas Anteriores
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etApiSecret: TextInputEditText
    private lateinit var etAmount: TextInputEditText
    private lateinit var etRisk: TextInputEditText // 🆕 NUEVO: Riesgo
    private lateinit var etTp: TextInputEditText
    private lateinit var etSl: TextInputEditText
    private lateinit var sbLeverage: SeekBar
    private lateinit var tvLeverageLabel: TextView
    private lateinit var switchTurbo: Switch
    private lateinit var btnSave: Button

    // Selector de Estrategia
    private lateinit var rgStrategy: RadioGroup
    private lateinit var rbModerada: RadioButton
    private lateinit var rbAgresiva: RadioButton
    private lateinit var rbBreakout: RadioButton

    // Selector Multi-Moneda
    private lateinit var cbBTC: CheckBox
    private lateinit var cbETH: CheckBox
    private lateinit var cbSOL: CheckBox
    private lateinit var cbXRP: CheckBox

    // Parámetros Avanzados
    private lateinit var spinnerTimeframe: Spinner
    private lateinit var etTrailingStop: TextInputEditText
    private lateinit var etCircuitBreaker: TextInputEditText
    private lateinit var switchPauseOnLoss: MaterialSwitch

    private val timeframes = arrayOf("1m", "5m", "15m", "30m", "1H")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Vincular Vistas
        etApiKey = view.findViewById(R.id.etApiKey)
        etApiSecret = view.findViewById(R.id.etApiSecret)
        etAmount = view.findViewById(R.id.etAmount)
        etRisk = view.findViewById(R.id.etRisk) // 🆕 VINCULADO
        etTp = view.findViewById(R.id.etTp)
        etSl = view.findViewById(R.id.etSl)
        sbLeverage = view.findViewById(R.id.sbLeverage)
        tvLeverageLabel = view.findViewById(R.id.tvLeverageLabel)
        switchTurbo = view.findViewById(R.id.switchTurbo)
        btnSave = view.findViewById(R.id.btnSave)

        rgStrategy = view.findViewById(R.id.rgStrategy)
        rbModerada = view.findViewById(R.id.rbModerada)
        rbAgresiva = view.findViewById(R.id.rbAgresiva)
        rbBreakout = view.findViewById(R.id.rbBreakout)

        cbBTC = view.findViewById(R.id.cbBTC)
        cbETH = view.findViewById(R.id.cbETH)
        cbSOL = view.findViewById(R.id.cbSOL)
        cbXRP = view.findViewById(R.id.cbXRP)

        // Avanzados
        spinnerTimeframe = view.findViewById(R.id.spinnerTimeframe)
        etTrailingStop = view.findViewById(R.id.etTrailingStop)
        etCircuitBreaker = view.findViewById(R.id.etCircuitBreaker)
        switchPauseOnLoss = view.findViewById(R.id.switchPauseOnLoss)

        // Configurar Spinner de Temporalidad
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, timeframes)
        spinnerTimeframe.adapter = adapter

        // 2. Cargar Datos Guardados
        val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("API_KEY", ""))
        etApiSecret.setText(prefs.getString("SECRET_KEY", ""))
        etAmount.setText(prefs.getString("AMOUNT", "1000"))

        // 🆕 CARGAR RIESGO (Por defecto 5.0%)
        etRisk.setText(prefs.getString("RISK_PERCENT", "5.0"))

        etTp.setText(prefs.getString("TP_VAL", "2.0"))
        etSl.setText(prefs.getString("SL_VAL", "1.5"))

        val lev = prefs.getInt("LEVERAGE", 10)
        sbLeverage.progress = lev
        tvLeverageLabel.text = "Apalancamiento: ${lev}x"
        switchTurbo.isChecked = prefs.getBoolean("TURBO_MODE", true)

        val estrategia = prefs.getString("STRATEGY", "AGRESIVA")
        when (estrategia) {
            "MODERADA" -> rbModerada.isChecked = true
            "BREAKOUT" -> rbBreakout.isChecked = true
            else -> rbAgresiva.isChecked = true
        }

        cbBTC.isChecked = prefs.getBoolean("COIN_BTC", true)
        cbETH.isChecked = prefs.getBoolean("COIN_ETH", false)
        cbSOL.isChecked = prefs.getBoolean("COIN_SOL", true)
        cbXRP.isChecked = prefs.getBoolean("COIN_XRP", true)

        // Cargar Datos Avanzados
        val savedTf = prefs.getString("TIMEFRAME", "1m")
        spinnerTimeframe.setSelection(timeframes.indexOf(savedTf).takeIf { it >= 0 } ?: 0)

        etTrailingStop.setText(prefs.getFloat("TS_ACTIV", 1.3f).toString())
        etCircuitBreaker.setText(prefs.getFloat("MAX_DAILY_LOSS", 10.0f).toString())
        switchPauseOnLoss.isChecked = prefs.getBoolean("PAUSE_ON_LOSS", true)

        // Listener Slider
        sbLeverage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val valor = if (progress < 1) 1 else progress
                tvLeverageLabel.text = "Apalancamiento: ${valor}x"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 3. Botón Guardar
        btnSave.setOnClickListener {
            val editor = prefs.edit()

            // Datos Básicos
            editor.putString("API_KEY", etApiKey.text.toString())
            editor.putString("SECRET_KEY", etApiSecret.text.toString())
            editor.putString("AMOUNT", etAmount.text.toString())

            // 🆕 GUARDAR RIESGO
            editor.putString("RISK_PERCENT", etRisk.text.toString())

            editor.putString("TP_VAL", etTp.text.toString())
            editor.putString("SL_VAL", etSl.text.toString())

            val currentLev = if (sbLeverage.progress < 1) 1 else sbLeverage.progress
            editor.putInt("LEVERAGE", currentLev)
            editor.putBoolean("TURBO_MODE", switchTurbo.isChecked)

            val selectedStrategy = when (rgStrategy.checkedRadioButtonId) {
                R.id.rbModerada -> "MODERADA"
                R.id.rbBreakout -> "BREAKOUT"
                else -> "AGRESIVA"
            }
            editor.putString("STRATEGY", selectedStrategy)

            editor.putBoolean("COIN_BTC", cbBTC.isChecked)
            editor.putBoolean("COIN_ETH", cbETH.isChecked)
            editor.putBoolean("COIN_SOL", cbSOL.isChecked)
            editor.putBoolean("COIN_XRP", cbXRP.isChecked)

            // Guardar Datos Avanzados
            editor.putString("TIMEFRAME", spinnerTimeframe.selectedItem.toString())
            editor.putFloat("TS_ACTIV", etTrailingStop.text.toString().toFloatOrNull() ?: 1.3f)
            editor.putFloat("MAX_DAILY_LOSS", etCircuitBreaker.text.toString().toFloatOrNull() ?: 10.0f)
            editor.putBoolean("PAUSE_ON_LOSS", switchPauseOnLoss.isChecked)

            editor.apply()

            Toast.makeText(requireContext(), "✅ Configuración Guardada", Toast.LENGTH_SHORT).show()
        }
    }
}