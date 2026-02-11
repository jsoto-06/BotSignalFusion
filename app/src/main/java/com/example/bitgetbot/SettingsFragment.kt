package com.example.bitgetbot

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText

class SettingsFragment : Fragment() {

    private lateinit var etApiKey: TextInputEditText
    private lateinit var etApiSecret: TextInputEditText
    private lateinit var etAmount: TextInputEditText
    private lateinit var etTp: TextInputEditText
    private lateinit var etSl: TextInputEditText
    private lateinit var sbLeverage: SeekBar
    private lateinit var tvLeverageLabel: TextView
    private lateinit var switchTurbo: Switch
    private lateinit var btnSave: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Vincular vistas
        etApiKey = view.findViewById(R.id.etApiKey)
        etApiSecret = view.findViewById(R.id.etApiSecret)
        etAmount = view.findViewById(R.id.etAmount)
        etTp = view.findViewById(R.id.etTp)
        etSl = view.findViewById(R.id.etSl)
        sbLeverage = view.findViewById(R.id.sbLeverage)
        tvLeverageLabel = view.findViewById(R.id.tvLeverageLabel)
        switchTurbo = view.findViewById(R.id.switchTurbo)
        btnSave = view.findViewById(R.id.btnSave)

        // 2. Cargar datos guardados
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

        // 3. Listener del Slider
        sbLeverage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Mínimo 1x
                val valor = if (progress < 1) 1 else progress
                tvLeverageLabel.text = "Apalancamiento: ${valor}x"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 4. Guardar Cambios
        btnSave.setOnClickListener {
            val editor = prefs.edit()
            editor.putString("API_KEY", etApiKey.text.toString())
            editor.putString("SECRET_KEY", etApiSecret.text.toString())
            editor.putString("AMOUNT", etAmount.text.toString())
            editor.putString("TP_VAL", etTp.text.toString())
            editor.putString("SL_VAL", etSl.text.toString())

            // Asegurar mínimo 1x
            val currentLev = if (sbLeverage.progress < 1) 1 else sbLeverage.progress
            editor.putInt("LEVERAGE", currentLev)

            editor.putBoolean("TURBO_MODE", switchTurbo.isChecked)
            editor.apply()

            // Actualizar variables en memoria del bot (si está corriendo)
            TradingService.currentBalance = etAmount.text.toString().toDoubleOrNull() ?: 500.0

            Toast.makeText(requireContext(), "✅ Configuración Guardada", Toast.LENGTH_SHORT).show()
        }
    }
}