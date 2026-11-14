package com.cita.sescam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var etCip: EditText
    private lateinit var tvDiasTitulo: TextView
    private lateinit var etDias: EditText
    private lateinit var tvRangoPreview: TextView
    private lateinit var etIntervalo: EditText
    private lateinit var rg: RadioGroup
    private lateinit var cbMejorar: CheckBox
    private lateinit var cbReservar: CheckBox
    private lateinit var tvEstado: TextView
    private lateinit var tvHistorial: TextView
    private lateinit var boton: Button

    private val oyente = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refrescar() }
    }

    private fun tarjeta(bgColor: Int, radiusDp: Float, padPx: Int): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padPx, padPx, padPx, padPx)
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = radiusDp * d
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, (14 * d).toInt())
            layoutParams = lp
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Fijar color de barra de estado y barra de navegación para evitar tonos beige
        window.statusBarColor = Color.parseColor("#004D4D")

        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val colorPrimario = Color.parseColor("#006A6A")
        val colorFondo = Color.parseColor("#F4F7F7")

        val root = ScrollView(this).apply {
            setBackgroundColor(colorFondo)
            isFillViewport = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (20 * d).toInt(), pad, (24 * d).toInt())
            setBackgroundColor(colorFondo)
        }
        root.addView(col)
        setContentView(root)

        // Cabecera Moderna
        val header = tarjeta(colorPrimario, 16f, pad)
        header.addView(TextView(this).apply {
            text = "🏥 Cita Automática SESCAM"
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        header.addView(TextView(this).apply {
            text = "Vigilancia inteligente de huecos en Atención Primaria"
            textSize = 13.5f
            setTextColor(Color.parseColor("#E0F2F1"))
            setPadding(0, (6 * d).toInt(), 0, 0)
        })
        col.addView(header)

        fun titulo(contenedor: LinearLayout, t: String, topMarginDp: Int = 10): TextView {
            val tv = TextView(this).apply {
                text = t
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#263238"))
                setPadding(0, (topMarginDp * d).toInt(), 0, (4 * d).toInt())
            }
            contenedor.addView(tv)
            return tv
        }

        // Tarjeta de Configuración
        val cardCfg = tarjeta(Color.WHITE, 16f, pad)
        col.addView(cardCfg)

        titulo(cardCfg, "Tu código CIP (Sanitario)", 0)
        etCip = EditText(this).apply {
            setText(prefs.getString("cip", ""))
            hint = "Ej. ABCD1234567890"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine()
            setPadding((12*d).toInt(), (10*d).toInt(), (12*d).toInt(), (10*d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FAFAFA"))
                setStroke(1, Color.parseColor("#CFD8DC"))
                cornerRadius = 8 * d
            }
        }
        cardCfg.addView(etCip)

        titulo(cardCfg, "Modalidad de consulta", 14)
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
        cardCfg.addView(rg)

        titulo(cardCfg, "Reglas de búsqueda", 14)
        cbMejorar = CheckBox(this).apply {
            text = "Solo avisar si mejora la cita más cercana actual (ignora días)"
            isChecked = prefs.getBoolean("mejorar", false)
            textSize = 13f
        }
        cardCfg.addView(cbMejorar)

        tvDiasTitulo = titulo(cardCfg, "Días hacia delante para buscar:", 10)
        etDias = EditText(this).apply {
            setText(prefs.getInt("dias", 20).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setPadding((12*d).toInt(), (10*d).toInt(), (12*d).toInt(), (10*d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FAFAFA"))
                setStroke(1, Color.parseColor("#CFD8DC"))
                cornerRadius = 8 * d
            }
        }
        cardCfg.addView(etDias)

        // Vista previa clara de hasta qué fecha y hora buscará
        tvRangoPreview = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#00796B"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, (6 * d).toInt(), 0, (4 * d).toInt())
        }
        cardCfg.addView(tvRangoPreview)

        fun actualizarPreviewRango() {
            val diasVal = etDias.text.toString().toIntOrNull() ?: 20
            val fechaLimite = LocalDate.now().plusDays(diasVal.toLong())
            val formato = DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' yyyy", Locale("es", "ES"))
            tvRangoPreview.text = "📅 Buscará citas desde hoy hasta el ${fechaLimite.format(formato)} a las 23:59"
        }
        actualizarPreviewRango()

        etDias.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { actualizarPreviewRango() }
            override fun afterTextChanged(s: Editable?) {}
        })

        fun actualizarEstadoDias() {
            val bloqueado = cbMejorar.isChecked
            etDias.isEnabled = !bloqueado
            etDias.alpha = if (bloqueado) 0.4f else 1.0f
            tvDiasTitulo.alpha = if (bloqueado) 0.4f else 1.0f
            tvRangoPreview.visibility = if (bloqueado) View.GONE else View.VISIBLE
        }
        actualizarEstadoDias()
        cbMejorar.setOnCheckedChangeListener { _, _ -> actualizarEstadoDias() }

        cbReservar = CheckBox(this).apply {
            text = "Reservar automáticamente el primer hueco y detener"
            isChecked = prefs.getBoolean("reservar", false)
            textSize = 13f
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        cardCfg.addView(cbReservar)

        titulo(cardCfg, "Intervalo entre comprobaciones (segundos, mín. 30)", 14)
        etIntervalo = EditText(this).apply {
            setText(prefs.getInt("intervalo", 30).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setPadding((12*d).toInt(), (10*d).toInt(), (12*d).toInt(), (10*d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FAFAFA"))
                setStroke(1, Color.parseColor("#CFD8DC"))
                cornerRadius = 8 * d
            }
        }
        cardCfg.addView(etIntervalo)

        // Botón principal
        boton = Button(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(pad, (14 * d).toInt(), pad, (14 * d).toInt())
        }
        val lpBtn = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lpBtn.setMargins(0, 0, 0, (14 * d).toInt())
        boton.layoutParams = lpBtn
        boton.setOnClickListener {
            if (prefs.getBoolean("activo", false)) parar() else iniciar()
        }
        col.addView(boton)

        // Tarjeta de Estado e Historial
        val cardEstado = tarjeta(Color.WHITE, 16f, pad)
        col.addView(cardEstado)

        titulo(cardEstado, "Estado en tiempo real", 0)
        tvEstado = TextView(this).apply {
            textSize = 14.5f
            setTextColor(colorPrimario)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, (4 * d).toInt(), 0, (12 * d).toInt())
        }
        cardEstado.addView(tvEstado)

        titulo(cardEstado, "Historial de comprobaciones", 4)
        tvHistorial = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#546E7A"))
            setPadding(0, (2 * d).toInt(), 0, (10 * d).toInt())
        }
        cardEstado.addView(tvHistorial)

        val btnLimpiar = Button(this).apply {
            text = "Limpiar historial"
            textSize = 12f
            setTextColor(Color.parseColor("#006A6A"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E0F2F1"))
                cornerRadius = 8 * d
            }
            setOnClickListener {
                prefs.edit().putString("historial", "").apply()
                refrescar()
            }
        }
        cardEstado.addView(btnLimpiar)

        // Botón de batería estilizado
        val btnBateria = Button(this).apply {
            text = "⚡ Quitar restricciones de batería (Recomendado)"
            textSize = 13f
            setTextColor(Color.parseColor("#37474F"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#ECEFF1"))
                cornerRadius = 10 * d
            }
            setPadding(pad, (12 * d).toInt(), pad, (12 * d).toInt())
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        val lpBat = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        btnBateria.layoutParams = lpBat
        col.addView(btnBateria)
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
            Toast.makeText(this, "Escribe tu código CIP correctamente", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit()
            .putString("cip", cip)
            .putInt("dias", etDias.text.toString().toIntOrNull() ?: 20)
            .putInt("intervalo", etIntervalo.text.toString().toIntOrNull() ?: 30)
            .putString("modo", modoElegido())
            .putBoolean("mejorar", cbMejorar.isChecked)
            .putBoolean("reservar", cbReservar.isChecked)
            .putBoolean("activo", true)
            .putString("estado", "Arrancando búsqueda…")
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
        prefs.edit().putBoolean("activo", false).putString("estado", "Búsqueda detenida").apply()
        refrescar()
    }

    private fun refrescar() {
        val d = resources.displayMetrics.density
        val activo = prefs.getBoolean("activo", false)
        boton.text = if (activo) "🛑 PARAR BÚSQUEDA" else "🚀 INICIAR BÚSQUEDA"
        boton.background = GradientDrawable().apply {
            setColor(if (activo) Color.parseColor("#D32F2F") else Color.parseColor("#006A6A"))
            cornerRadius = 12f * d
        }
        tvEstado.text = prefs.getString("estado", "Sin iniciar")
        val hist = prefs.getString("historial", "") ?: ""
        tvHistorial.text = if (hist.isBlank()) "Aún no hay registros en esta sesión." else hist
    }
}
