package com.example.signalfusion

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class SettingsFragment : Fragment() {

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
    private lateinit var switchDemoMode: SwitchMaterial
    private lateinit var tvDemoIndicator: TextView

    private val timeframes = arrayOf("1m", "5m", "15m", "30m", "1h")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etApiKey    = view.findViewById(R.id.etApiKey)
        etApiSecret = view.findViewById(R.id.etApiSecret)
        try { etApiPassphrase = view.findViewById(R.id.etApiPassphrase) } catch (e: Exception) {}

        etRisk          = view.findViewById(R.id.etRisk)
        etTp            = view.findViewById(R.id.etTp)
        etSl            = view.findViewById(R.id.etSl)
        sbLeverage      = view.findViewById(R.id.sbLeverage)
        tvLeverageLabel = view.findViewById(R.id.tvLeverageLabel)
        btnSave         = view.findViewById(R.id.btnSave)
        rgStrategy      = view.findViewById(R.id.rgStrategy)
        cbBTC           = view.findViewById(R.id.cbBTC)
        cbETH           = view.findViewById(R.id.cbETH)
        cbSOL           = view.findViewById(R.id.cbSOL)
        cbXRP           = view.findViewById(R.id.cbXRP)
        spinnerTimeframe = view.findViewById(R.id.spinnerTimeframe)

        try {
            switchDemoMode  = view.findViewById(R.id.switchDemoMode)
            tvDemoIndicator = view.findViewById(R.id.tvDemoIndicator)
        } catch (e: Exception) {}

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            timeframes
        )
        spinnerTimeframe.adapter = adapter

        val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)

        etApiKey.setText(prefs.getString("API_KEY", ""))
        etApiSecret.setText(prefs.getString("SECRET_KEY", ""))
        if (::etApiPassphrase.isInitialized) {
            etApiPassphrase.setText(prefs.getString("API_PASSPHRASE", ""))
        }

        etRisk.setText(prefs.getString("RISK_PERCENT", "5.0"))

        // ✅ V9: Defaults coherentes con scalping en 15m (TP 2.0% / SL 0.9%)
        etTp.setText(prefs.getString("TP_VAL", V9TradingParams.TP_SCALPING.toString()))
        etSl.setText(prefs.getString("SL_VAL", V9TradingParams.SL_SCALPING.toString()))

        val lev = prefs.getInt("LEVERAGE", 10)
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

        val estrategia = prefs.getString("STRATEGY", "MODERADA")
        when (estrategia) {
            "MODERADA" -> rgStrategy.check(R.id.rbModerada)
            "BREAKOUT" -> rgStrategy.check(R.id.rbBreakout)
            "AGRESIVA" -> rgStrategy.check(R.id.rbAgresiva)
            else       -> rgStrategy.check(R.id.rbModerada)
        }

        cbBTC.isChecked = prefs.getBoolean("COIN_BTC", true)
        cbETH.isChecked = prefs.getBoolean("COIN_ETH", false)
        cbSOL.isChecked = prefs.getBoolean("COIN_SOL", false)
        cbXRP.isChecked = prefs.getBoolean("COIN_XRP", false)

        val savedTf = prefs.getString("TIMEFRAME_VAL", "15m")
        val spinnerPos = adapter.getPosition(savedTf)
        if (spinnerPos >= 0) spinnerTimeframe.setSelection(spinnerPos)

        if (::switchDemoMode.isInitialized) {
            setupDemoSwitch(prefs)
        }

        btnSave.setOnClickListener { guardarConfiguracion() }
    }

    private fun setupDemoSwitch(prefs: android.content.SharedPreferences) {
        BitgetConfig.loadFromPreferences(requireContext())
        val isDemo = BitgetConfig.isDemo()
        switchDemoMode.isChecked = !isDemo
        actualizarIndicadorDemo(isDemo)

        switchDemoMode.setOnCheckedChangeListener { _, isChecked ->
            val nuevoModo = !isChecked  // Switch ON = REAL, OFF = DEMO
            if (!nuevoModo) {
                mostrarAdvertenciaReal {
                    BitgetConfig.saveToPreferences(requireContext(), false)
                    actualizarIndicadorDemo(false)
                    Toast.makeText(requireContext(), "⚠️ MODO REAL ACTIVADO", Toast.LENGTH_LONG).show()
                }
            } else {
                BitgetConfig.saveToPreferences(requireContext(), true)
                actualizarIndicadorDemo(true)
                Toast.makeText(requireContext(), "📊 MODO DEMO ACTIVADO", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun actualizarIndicadorDemo(isDemo: Boolean) {
        if (::tvDemoIndicator.isInitialized) {
            tvDemoIndicator.text = BitgetConfig.getModeIndicator()
            try {
                tvDemoIndicator.setTextColor(
                    android.graphics.Color.parseColor(BitgetConfig.getModeColor())
                )
            } catch (e: Exception) {}
        }
    }

    private fun mostrarAdvertenciaReal(onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle("⚠️ ADVERTENCIA: MODO REAL")
            .setMessage(
                "Vas a operar con DINERO REAL.\n\n" +
                        "Asegúrate de que:\n" +
                        "✅ El bot funcionó bien en DEMO\n" +
                        "✅ Entiendes los riesgos del trading\n" +
                        "✅ Puedes permitirte perder el capital\n\n" +
                        "¿Estás seguro de continuar?"
            )
            .setPositiveButton("SÍ, CONTINUAR") { dialog, _ ->
                onConfirm()
                dialog.dismiss()
            }
            .setNegativeButton("NO, CANCELAR") { dialog, _ ->
                switchDemoMode.isChecked = false
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun guardarConfiguracion() {
        val prefs = requireContext().getSharedPreferences("BotConfig", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        editor.putString("API_KEY", etApiKey.text.toString().trim())
        editor.putString("SECRET_KEY", etApiSecret.text.toString().trim())
        if (::etApiPassphrase.isInitialized) {
            editor.putString("API_PASSPHRASE", etApiPassphrase.text.toString().trim())
        }

        // Risk: máximo 10%
        val riskValue = etRisk.text.toString().toDoubleOrNull() ?: 5.0
        val finalRisk = if (riskValue > 10.0) {
            Toast.makeText(requireContext(), "⚠️ Risk limitado al 10% por seguridad", Toast.LENGTH_LONG).show()
            "10.0"
        } else {
            riskValue.toString()
        }
        editor.putString("RISK_PERCENT", finalRisk)

        val tpValue = etTp.text.toString().toDoubleOrNull() ?: V9TradingParams.TP_SCALPING
        val slValue = etSl.text.toString().toDoubleOrNull() ?: V9TradingParams.SL_SCALPING

        // ✅ V9: Validación R:R mínimo 1.5:1 con los nuevos valores por defecto
        if (tpValue < slValue * 1.5) {
            Toast.makeText(
                requireContext(),
                "⚠️ TP debe ser al menos 1.5x el SL para R:R positivo\n" +
                        "Aplicando valores V9: TP ${V9TradingParams.TP_SCALPING}% / SL ${V9TradingParams.SL_SCALPING}%",
                Toast.LENGTH_LONG
            ).show()
            editor.putString("TP_VAL", V9TradingParams.TP_SCALPING.toString())
            editor.putString("SL_VAL", V9TradingParams.SL_SCALPING.toString())
        } else {
            editor.putString("TP_VAL", tpValue.toString())
            editor.putString("SL_VAL", slValue.toString())
        }

        // ✅ Cap duro de apalancamiento en 20x (protección extra)
        val rawLev = if (sbLeverage.progress < 1) 1 else sbLeverage.progress
        val finalLev = minOf(rawLev, 20)
        if (rawLev > 20) {
            Toast.makeText(requireContext(), "⚠️ Apalancamiento limitado a 20x", Toast.LENGTH_SHORT).show()
        }
        editor.putInt("LEVERAGE", finalLev)

        val selectedStrategy = when (rgStrategy.checkedRadioButtonId) {
            R.id.rbModerada -> "MODERADA"
            R.id.rbBreakout -> "BREAKOUT"
            R.id.rbAgresiva -> "AGRESIVA"
            else            -> "MODERADA"
        }
        editor.putString("STRATEGY", selectedStrategy)

        editor.putBoolean("COIN_BTC", cbBTC.isChecked)
        editor.putBoolean("COIN_ETH", cbETH.isChecked)
        editor.putBoolean("COIN_SOL", cbSOL.isChecked)
        editor.putBoolean("COIN_XRP", cbXRP.isChecked)

        editor.putString("TIMEFRAME_VAL", spinnerTimeframe.selectedItem.toString())
        editor.apply()

        val modoActual = if (BitgetConfig.isDemo()) "DEMO 📊" else "REAL 💰"
        Toast.makeText(
            requireContext(),
            "✅ Configuración Guardada\n" +
                    "Modo: $modoActual | TP: ${tpValue}% / SL: ${slValue}%",
            Toast.LENGTH_SHORT
        ).show()
    }
}