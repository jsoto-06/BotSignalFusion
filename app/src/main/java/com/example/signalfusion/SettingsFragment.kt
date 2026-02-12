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

    // Vistas anteriores
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etApiSecret: TextInputEditText
    private lateinit var etAmount: TextInputEditText
    private lateinit var etTp: TextInputEditText
    private lateinit var etSl: TextInputEditText
    private lateinit var sbLeverage: SeekBar
    private lateinit var tvLeverageLabel: TextView
    private lateinit var switchTurbo: Switch // A veces es MaterialSwitch, si da error cambia a Switch o viceversa
    private lateinit var btnSave: Button

    // Selector de Estrategia
    private lateinit var rgStrategy: RadioGroup
    private lateinit var rbModerada: RadioButton
    private lateinit var rbAgresiva: RadioButton
    private lateinit var rbBreakout: RadioButton

    // 🆕 NUEVO: Selector Multi-Moneda
    private lateinit var cbBTC: CheckBox
    private lateinit var cbETH: CheckBox
    private lateinit var cbSOL: CheckBox
    private lateinit var cbXRP: CheckBox

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Vincular Vistas Existentes
        etApiKey = view.findViewById(R.id.etApiKey)
        etApiSecret = view.findViewById(R.id.etApiSecret)
        etAmount = view.findViewById(R.id.etAmount)
        etTp = view.findViewById(R.id.etTp)
        etSl = view.findViewById(R.id.etSl)
        sbLeverage = view.findViewById(R.id.sbLeverage)
        tvLeverageLabel = view.findViewById(R.id.tvLeverageLabel)
        switchTurbo = view.findViewById(R.id.switchTurbo) // Asegúrate que el ID en XML sea switchTurbo
        btnSave = view.findViewById(R.id.btnSave)

        rgStrategy = view.findViewById(R.id.rgStrategy)
        rbModerada = view.findViewById(R.id.rbModerada)
        rbAgresiva = view.findViewById(R.id.rbAgresiva)
        rbBreakout = view.findViewById(R.id.rbBreakout)

        // 🆕 1.1 Vincular CheckBoxes (Multi-Moneda)
        cbBTC = view.findViewById(R.id.cbBTC)
        cbETH = view.findViewById(R.id.cbETH)
        cbSOL = view.findViewById(R.id.cbSOL)
        cbXRP = view.findViewById(R.id.cbXRP)

        // 2. Cargar Datos Guardados
        val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("API_KEY", ""))
        etApiSecret.setText(prefs.getString("SECRET_KEY", ""))
        etAmount.setText(prefs.getString("AMOUNT", "1000"))
        etTp.setText(prefs.getString("TP_VAL", "2.0"))
        etSl.setText(prefs.getString("SL_VAL", "1.5"))

        val lev = prefs.getInt("LEVERAGE", 10)
        sbLeverage.progress = lev
        tvLeverageLabel.text = "Apalancamiento: ${lev}x"
        switchTurbo.isChecked = prefs.getBoolean("TURBO_MODE", false)

        // Cargar Estrategia
        val estrategia = prefs.getString("STRATEGY", "MODERADA")
        when (estrategia) {
            "AGRESIVA" -> rbAgresiva.isChecked = true
            "BREAKOUT" -> rbBreakout.isChecked = true
            else -> rbModerada.isChecked = true
        }

        // 🆕 2.1 Cargar Monedas Seleccionadas (BTC true por defecto)
        cbBTC.isChecked = prefs.getBoolean("COIN_BTC", true)
        cbETH.isChecked = prefs.getBoolean("COIN_ETH", false)
        cbSOL.isChecked = prefs.getBoolean("COIN_SOL", false)
        cbXRP.isChecked = prefs.getBoolean("COIN_XRP", false)

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
            editor.putString("API_KEY", etApiKey.text.toString())
            editor.putString("SECRET_KEY", etApiSecret.text.toString())
            editor.putString("AMOUNT", etAmount.text.toString())
            editor.putString("TP_VAL", etTp.text.toString())
            editor.putString("SL_VAL", etSl.text.toString())

            val currentLev = if (sbLeverage.progress < 1) 1 else sbLeverage.progress
            editor.putInt("LEVERAGE", currentLev)
            editor.putBoolean("TURBO_MODE", switchTurbo.isChecked)

            // Guardar Estrategia
            val selectedStrategy = when (rgStrategy.checkedRadioButtonId) {
                R.id.rbAgresiva -> "AGRESIVA"
                R.id.rbBreakout -> "BREAKOUT"
                else -> "MODERADA"
            }
            editor.putString("STRATEGY", selectedStrategy)

            // 🆕 3.1 Guardar Selección de Monedas
            editor.putBoolean("COIN_BTC", cbBTC.isChecked)
            editor.putBoolean("COIN_ETH", cbETH.isChecked)
            editor.putBoolean("COIN_SOL", cbSOL.isChecked)
            editor.putBoolean("COIN_XRP", cbXRP.isChecked)

            editor.apply()

            // Feedback al usuario
            Toast.makeText(requireContext(), "✅ Configuración Guardada", Toast.LENGTH_SHORT).show()
        }
    }
}