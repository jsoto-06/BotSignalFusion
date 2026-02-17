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
    private lateinit var etApiPassphrase: TextInputEditText // 🔥 NUEVO: Passphrase

    private lateinit var etAmount: TextInputEditText
    private lateinit var etRisk: TextInputEditText
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
        // Asegúrate de usar el layout correcto (fragment_settings)
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Vincular Vistas
        etApiKey = view.findViewById(R.id.etApiKey)
        etApiSecret = view.findViewById(R.id.etApiSecret)

        // Intentamos vincular la Passphrase. Si el ID no existe en el XML viejo, no crashea.
        try {
            etApiPassphrase = view.findViewById(R.id.etApiPassphrase)
        } catch (e: Exception) {
            // Si usas un XML antiguo que no tiene este campo, esto evita el crash
        }

        etAmount = view.findViewById(R.id.etAmount)
        etRisk = view.findViewById(R.id.etRisk)
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

        // Cargar Passphrase si existe la vista
        if (::etApiPassphrase.isInitialized) {
            etApiPassphrase.setText(prefs.getString("API_PASSPHRASE", ""))
        }

        etAmount.setText(prefs.getString("AMOUNT", "1000"))
        etRisk.setText(prefs.getString("RISK_PERCENT", "5.0"))
        etTp.setText(prefs.getString("TP_VAL", "2.0"))
        etSl.setText(prefs.getString("SL_VAL", "1.5"))

        val lev = prefs.getInt("LEVERAGE", 5)
        sbLeverage.progress = lev
        tvLeverageLabel.text = "Apalancamiento: ${lev}x"

        // Actualizamos el slider visualmente
        sbLeverage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val valor = if (progress < 1) 1 else progress
                tvLeverageLabel.text = "Apalancamiento: ${valor}x"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        switchTurbo.isChecked = prefs.getBoolean("TURBO_MODE", false)

        val estrategia = prefs.getString("STRATEGY", "MODERADA")
        when (estrategia) {
            "MODERADA" -> rbModerada.isChecked = true
            "BREAKOUT" -> rbBreakout.isChecked = true
            "AGRESIVA" -> rbAgresiva.isChecked = true
        }

        cbBTC.isChecked = prefs.getBoolean("COIN_BTC", true)
        cbETH.isChecked = prefs.getBoolean("COIN_ETH", false)
        cbSOL.isChecked = prefs.getBoolean("COIN_SOL", false)
        cbXRP.isChecked = prefs.getBoolean("COIN_XRP", false)

        val savedTf = prefs.getString("TIMEFRAME", "1m")
        val spinnerPosition = adapter.getPosition(savedTf)
        if (spinnerPosition >= 0) spinnerTimeframe.setSelection(spinnerPosition)

        etTrailingStop.setText(prefs.getFloat("TS_ACTIV", 1.3f).toString())
        etCircuitBreaker.setText(prefs.getFloat("MAX_DAILY_LOSS", 10.0f).toString())
        switchPauseOnLoss.isChecked = prefs.getBoolean("PAUSE_ON_LOSS", true)

        // 3. Botón Guardar
        btnSave.setOnClickListener {
            val editor = prefs.edit()

            // Datos Básicos
            editor.putString("API_KEY", etApiKey.text.toString().trim())
            editor.putString("SECRET_KEY", etApiSecret.text.toString().trim())

            // Guardar Passphrase
            if (::etApiPassphrase.isInitialized) {
                editor.putString("API_PASSPHRASE", etApiPassphrase.text.toString().trim())
            }

            editor.putString("AMOUNT", etAmount.text.toString())
            editor.putString("RISK_PERCENT", etRisk.text.toString())
            editor.putString("TP_VAL", etTp.text.toString())
            editor.putString("SL_VAL", etSl.text.toString())

            val currentLev = if (sbLeverage.progress < 1) 1 else sbLeverage.progress
            editor.putInt("LEVERAGE", currentLev)
            editor.putBoolean("TURBO_MODE", switchTurbo.isChecked)

            val selectedStrategy = when (rgStrategy.checkedRadioButtonId) {
                R.id.rbModerada -> "MODERADA"
                R.id.rbBreakout -> "BREAKOUT"
                R.id.rbAgresiva -> "AGRESIVA"
                else -> "MODERADA"
            }
            editor.putString("STRATEGY", selectedStrategy)

            editor.putBoolean("COIN_BTC", cbBTC.isChecked)
            editor.putBoolean("COIN_ETH", cbETH.isChecked)
            editor.putBoolean("COIN_SOL", cbSOL.isChecked)
            editor.putBoolean("COIN_XRP", cbXRP.isChecked)

            editor.putString("TIMEFRAME", spinnerTimeframe.selectedItem.toString())

            val tsVal = etTrailingStop.text.toString().toFloatOrNull() ?: 1.3f
            editor.putFloat("TS_ACTIV", tsVal)

            val cbVal = etCircuitBreaker.text.toString().toFloatOrNull() ?: 10.0f
            editor.putFloat("MAX_DAILY_LOSS", cbVal)

            editor.putBoolean("PAUSE_ON_LOSS", switchPauseOnLoss.isChecked)

            editor.apply()

            Toast.makeText(requireContext(), "✅ Configuración Guardada", Toast.LENGTH_SHORT).show()
        }
    }
}