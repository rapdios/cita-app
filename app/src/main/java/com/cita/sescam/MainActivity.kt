package com.cita.sescam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var etCip: EditText
    private lateinit var etDias: EditText
    private lateinit var etIntervalo: EditText
    private lateinit var rg: RadioGroup
    private lateinit var cbMejorar: CheckBox
    private lateinit var cbReservar: CheckBox
    private lateinit var tvEstado: TextView
    private lateinit var boton: Button

    private val oyente = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refrescar() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(col) })

        fun titulo(t: String) {
            col.addView(TextView(this).apply {
                text = t
                textSize = 16f
                setPadding(0, pad, 0, 0)
            })
        }

        titulo("Tu CIP")
        etCip = EditText(this).apply {
            setText(prefs.getString("cip", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine()
        }
        col.addView(etCip)

        titulo("Tipo de cita")
        rg = RadioGroup(this)
        val modos = listOf(
            "ambos" to "Presencial y teleconsulta",
            "presencial" to "Solo presencial",
            "teleconsulta" to "Solo teleconsulta"
        )
        val guardado = prefs.getString("modo", "ambos")
        for ((clave, texto) in modos) {
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = texto
            rb.tag = clave
            rg.addView(rb)
            if (clave == guardado) rb.isChecked = true
        }
        col.addView(rg)

        cbMejorar = CheckBox(this).apply {
            text = "Avisar solo si aparece un hueco más cercano que el actual (ignora los días)"
            isChecked = prefs.getBoolean("mejorar", true)
        }
        col.addView(cbMejorar)

        cbReservar = CheckBox(this).apply {
            text = "Reservar automáticamente el primer hueco que aparezca (y parar)"
            isChecked = prefs.getBoolean("reservar", false)
        }
        col.addView(cbReservar)

        titulo("Días hacia delante (si no marcas la casilla de arriba)")
        etDias = EditText(this).apply {
            setText(prefs.getInt("dias", 4).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        col.addView(etDias)

        titulo("Segundos entre comprobaciones (mínimo 30)")
        etIntervalo = EditText(this).apply {
            setText(prefs.getInt("intervalo", 30).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        col.addView(etIntervalo)

        boton = Button(this)
        boton.setOnClickListener {
            if (prefs.getBoolean("activo", false)) parar() else iniciar()
        }
        col.addView(boton)

        tvEstado = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, pad)
        }
        col.addView(tvEstado)

        col.addView(Button(this).apply {
            text = "Quitar ahorro de batería (recomendado)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(oyente)
        refrescar()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(oyente)
        super.onPause()
    }

    private fun modoElegido(): String {
        val rb = findViewById<RadioButton>(rg.checkedRadioButtonId)
        return (rb?.tag as? String) ?: "ambos"
    }

    private fun iniciar() {
        val cip = etCip.text.toString().trim().uppercase()
        if (cip.length < 8) {
            Toast.makeText(this, "Escribe tu CIP", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit()
            .putString("cip", cip)
            .putInt("dias", etDias.text.toString().toIntOrNull() ?: 4)
            .putInt("intervalo", etIntervalo.text.toString().toIntOrNull() ?: 30)
            .putString("modo", modoElegido())
            .putBoolean("mejorar", cbMejorar.isChecked)
            .putBoolean("reservar", cbReservar.isChecked)
            .putBoolean("activo", true)
            .putString("estado", "Arrancando…")
            .apply()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, WatchService::class.java))
        refrescar()
    }

    private fun parar() {
        stopService(Intent(this, WatchService::class.java))
        prefs.edit().putBoolean("activo", false).putString("estado", "Parado").apply()
        refrescar()
    }

    private fun refrescar() {
        val activo = prefs.getBoolean("activo", false)
        boton.text = if (activo) "PARAR búsqueda" else "INICIAR búsqueda"
        tvEstado.text = prefs.getString("estado", "Sin iniciar")
    }
}
