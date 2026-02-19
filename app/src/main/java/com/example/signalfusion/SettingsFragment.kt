package com.example.signalfusion

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText

class SettingsFragment : Fragment() {

    // 1. Solo las vistas esenciales
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etApiSecret: TextInputEditText
    private lateinit var etApiPassphrase: TextInputEditText
    private lateinit var etRisk: TextInputEditText
    private lateinit var etTp: TextInputEditText
    private lateinit var etSl: TextInputEditText
    private lateinit var sbLeverage: SeekBar
    private lateinit var tvLeverageLabel: TextView
    private lateinit var btnSave: Button

    private lateinit var rgStrategy: RadioGroup
    private lateinit var cbBTC: CheckBox
    private lateinit var cbETH: CheckBox
    private lateinit var cbSOL: CheckBox
    private lateinit var cbXRP: CheckBox
    private lateinit var spinnerTimeframe: Spinner

    private val timeframes = arrayOf("1m", "5m", "15m", "30m", "1h")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 2. Vincular vistas (Asegúrate de borrar del XML los que ya no usamos)
        etApiKey = view.findViewById(R.id.etApiKey)
        etApiSecret = view.findViewById(R.id.etApiSecret)
        try { etApiPassphrase = view.findViewById(R.id.etApiPassphrase) } catch (e: Exception) {}

        etRisk = view.findViewById(R.id.etRisk)
        etTp = view.findViewById(R.id.etTp)
        etSl = view.findViewById(R.id.etSl)
        sbLeverage = view.findViewById(R.id.sbLeverage)
        tvLeverageLabel = view.findViewById(R.id.tvLeverageLabel)
        btnSave = view.findViewById(R.id.btnSave)

        rgStrategy = view.findViewById(R.id.rgStrategy)
        cbBTC = view.findViewById(R.id.cbBTC)
        cbETH = view.findViewById(R.id.cbETH)
        cbSOL = view.findViewById(R.id.cbSOL)
        cbXRP = view.findViewById(R.id.cbXRP)
        spinnerTimeframe = view.findViewById(R.id.spinnerTimeframe)

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, timeframes)
        spinnerTimeframe.adapter = adapter

        // 3. Cargar Datos Guardados
        val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("API_KEY", ""))
        etApiSecret.setText(prefs.getString("SECRET_KEY", ""))
        if (::etApiPassphrase.isInitialized) etApiPassphrase.setText(prefs.getString("API_PASSPHRASE", ""))

        etRisk.setText(prefs.getString("RISK_PERCENT", "50.0"))
        etTp.setText(prefs.getString("TP_VAL", "2.15"))
        etSl.setText(prefs.getString("SL_VAL", "1.65"))

        val lev = prefs.getInt("LEVERAGE", 5)
        sbLeverage.progress = lev
        tvLeverageLabel.text = "Apalancamiento: ${lev}x"

        sbLeverage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val valor = if (progress < 1) 1 else progress
                tvLeverageLabel.text = "Apalancamiento: ${valor}x"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 🔥 Cargar Estrategia Correctamente
        val estrategia = prefs.getString("STRATEGY", "AGRESIVA")
        when (estrategia) {
            "MODERADA" -> rgStrategy.check(R.id.rbModerada)
            "BREAKOUT" -> rgStrategy.check(R.id.rbBreakout)
            "AGRESIVA" -> rgStrategy.check(R.id.rbAgresiva)
            else -> rgStrategy.check(R.id.rbAgresiva)
        }

        cbBTC.isChecked = prefs.getBoolean("COIN_BTC", true)
        cbETH.isChecked = prefs.getBoolean("COIN_ETH", false)
        cbSOL.isChecked = prefs.getBoolean("COIN_SOL", false)
        cbXRP.isChecked = prefs.getBoolean("COIN_XRP", false)

        val savedTf = prefs.getString("TIMEFRAME_VAL", "5m")
        val spinnerPosition = adapter.getPosition(savedTf)
        if (spinnerPosition >= 0) spinnerTimeframe.setSelection(spinnerPosition)

        // 4. Botón Guardar
        btnSave.setOnClickListener {
            val editor = prefs.edit()

            editor.putString("API_KEY", etApiKey.text.toString().trim())
            editor.putString("SECRET_KEY", etApiSecret.text.toString().trim())
            if (::etApiPassphrase.isInitialized) editor.putString("API_PASSPHRASE", etApiPassphrase.text.toString().trim())

            editor.putString("RISK_PERCENT", etRisk.text.toString())
            editor.putString("TP_VAL", etTp.text.toString())
            editor.putString("SL_VAL", etSl.text.toString())

            val currentLev = if (sbLeverage.progress < 1) 1 else sbLeverage.progress
            editor.putInt("LEVERAGE", currentLev)

            // 🔥 Guardar Estrategia Seleccionada
            val selectedStrategy = when (rgStrategy.checkedRadioButtonId) {
                R.id.rbModerada -> "MODERADA"
                R.id.rbBreakout -> "BREAKOUT"
                R.id.rbAgresiva -> "AGRESIVA"
                else -> "AGRESIVA"
            }
            editor.putString("STRATEGY", selectedStrategy)

            editor.putBoolean("COIN_BTC", cbBTC.isChecked)
            editor.putBoolean("COIN_ETH", cbETH.isChecked)
            editor.putBoolean("COIN_SOL", cbSOL.isChecked)
            editor.putBoolean("COIN_XRP", cbXRP.isChecked)

            editor.putString("TIMEFRAME_VAL", spinnerTimeframe.selectedItem.toString())

            editor.apply()
            Toast.makeText(requireContext(), "✅ Configuración Guardada", Toast.LENGTH_SHORT).show()
        }
    }
}