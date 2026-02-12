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
    private lateinit var switchTurbo: Switch
    private lateinit var btnSave: Button

    // NUEVO: Selector de Estrategia
    private lateinit var rgStrategy: RadioGroup
    private lateinit var rbModerada: RadioButton
    private lateinit var rbAgresiva: RadioButton
    private lateinit var rbBreakout: RadioButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Vincular
        etApiKey = view.findViewById(R.id.etApiKey)
        etApiSecret = view.findViewById(R.id.etApiSecret)
        etAmount = view.findViewById(R.id.etAmount)
        etTp = view.findViewById(R.id.etTp)
        etSl = view.findViewById(R.id.etSl)
        sbLeverage = view.findViewById(R.id.sbLeverage)
        tvLeverageLabel = view.findViewById(R.id.tvLeverageLabel)
        switchTurbo = view.findViewById(R.id.switchTurbo)
        btnSave = view.findViewById(R.id.btnSave)

        // Vincular RadioGroup
        rgStrategy = view.findViewById(R.id.rgStrategy)
        rbModerada = view.findViewById(R.id.rbModerada)
        rbAgresiva = view.findViewById(R.id.rbAgresiva)
        rbBreakout = view.findViewById(R.id.rbBreakout)

        // 2. Cargar Datos
        val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("API_KEY", ""))
        etApiSecret.setText(prefs.getString("SECRET_KEY", ""))
        etAmount.setText(prefs.getString("AMOUNT", "500"))
        etTp.setText(prefs.getString("TP_VAL", "2.0"))
        etSl.setText(prefs.getString("SL_VAL", "1.5"))

        val lev = prefs.getInt("LEVERAGE", 5)
        sbLeverage.progress = lev
        tvLeverageLabel.text = "Apalancamiento: ${lev}x"
        switchTurbo.isChecked = prefs.getBoolean("TURBO_MODE", false)

        // Cargar Estrategia Seleccionada (Por defecto: MODERADA)
        val estrategia = prefs.getString("STRATEGY", "MODERADA")
        when (estrategia) {
            "AGRESIVA" -> rbAgresiva.isChecked = true
            "BREAKOUT" -> rbBreakout.isChecked = true
            else -> rbModerada.isChecked = true
        }

        // Listener Slider
        sbLeverage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val valor = if (progress < 1) 1 else progress
                tvLeverageLabel.text = "Apalancamiento: ${valor}x"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 3. Guardar
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

            editor.apply()
            Toast.makeText(requireContext(), "✅ Estrategia guardada: $selectedStrategy", Toast.LENGTH_SHORT).show()
        }
    }
}