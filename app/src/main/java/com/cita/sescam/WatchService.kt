package com.cita.sescam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class WatchService : Service() {

    @Volatile private var activo = false
    private var hilo: Thread? = null
    private var wl: PowerManager.WakeLock? = null

    companion object {
        const val CANAL_ESTADO = "estado"
        const val CANAL_AVISO = "aviso"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        crearCanales()
        startForeground(1, notifEstado("Vigilando huecos…"))
        if (!activo) {
            activo = true
            arrancar()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        activo = false
        hilo?.interrupt()
        wl?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun arrancar() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val cip = p.getString("cip", "") ?: ""
        val dias = p.getInt("dias", 4)
        val intervalo = maxOf(p.getInt("intervalo", 30), 30)
        val modo = p.getString("modo", "ambos") ?: "ambos"
        val mejorar = p.getBoolean("mejorar", false)
        val reservar = p.getBoolean("reservar", false)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cita:vigilar").apply {
            setReferenceCounted(false)
            acquire()
        }

        hilo = Thread {
            val cliente = SescamClient(cip)
            val vistos = HashSet<String>()
            var previo: String? = null
            var fallos = 0
            while (activo) {
                try {
                    val todos = cliente.huecos()
                    val hoy = LocalDate.now()
                    val lista = filtrar(todos, hoy, LocalDate.MAX, modo)
                    val nuevos: List<Hueco>
                    if (mejorar) {
                        val actual = lista.firstOrNull()?.clave ?: "9999-12-31 99:99"
                        val ant = previo
                        nuevos = if (ant != null && actual < ant) lista.filter { it.clave < ant } else emptyList()
                        previo = actual
                    } else {
                        val enRango = filtrar(todos, hoy, hoy.plusDays(dias.toLong()), modo)
                        nuevos = enRango.filter { vistos.add(it.clave) }
                    }
                    var reservada: JSONObject? = null
                    if (nuevos.isNotEmpty() && reservar) {
                        for (h in nuevos.take(5)) {
                            try {
                                reservada = cliente.reservar(h)
                                break
                            } catch (e: Exception) {
                                estado("No se pudo reservar ${h.texto}: ${e.message}")
                            }
                        }
                    }
                    if (reservada != null) {
                        val resumen = avisarReserva(reservada)
                        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                            .putBoolean("activo", false)
                            .putString("estado", "CITA RESERVADA: $resumen")
                            .apply()
                        registrarHistorial("CITA RESERVADA: $resumen")
                        activo = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                    if (nuevos.isNotEmpty()) {
                        avisar(nuevos)
                        estado("¡${nuevos.size} hueco(s) disponible(s)! ${nuevos.first().texto}")
                    } else {
                        val cercano = lista.firstOrNull()?.texto ?: "ninguno"
                        val detalleRango = if (!mejorar) " (rango $dias d)" else ""
                        estado("Sin novedades$detalleRango · más cercano: $cercano")
                    }
                    fallos = 0
                } catch (e: Exception) {
                    fallos++
                    estado("Error ($fallos): ${e.message}")
                }
                val espera = intervalo * minOf(1 shl minOf(fallos, 3), 8) + Random.nextInt(0, 15)
                dormir(espera.toLong())
            }
        }.also { it.start() }
    }

    private fun dormir(seg: Long) {
        var t = 0L
        try {
            while (activo && t < seg * 1000) {
                Thread.sleep(500)
                t += 500
            }
        } catch (e: InterruptedException) {
        }
    }

    private fun abrirApp(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun crearCanales() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL_ESTADO, "Estado de la búsqueda", NotificationManager.IMPORTANCE_LOW))
        val aviso = NotificationChannel(CANAL_AVISO, "Avisos de cita", NotificationManager.IMPORTANCE_HIGH)
        aviso.enableVibration(true)
        nm.createNotificationChannel(aviso)
    }

    private fun notifEstado(texto: String): Notification =
        Notification.Builder(this, CANAL_ESTADO)
            .setSmallIcon(R.drawable.ic_notif_cita)
            .setContentTitle("Buscando cita SESCAM")
            .setContentText(texto)
            .setContentIntent(abrirApp())
            .setOngoing(true)
            .build()

    private fun registrarHistorial(linea: String) {
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val actual = prefs.getString("historial", "") ?: ""
        val lineas = actual.split("\n").filter { it.isNotBlank() }.toMutableList()
        lineas.add(0, linea)
        val recortado = lineas.take(8).joinToString("\n")
        prefs.edit().putString("historial", recortado).apply()
    }

    private fun estado(msg: String) {
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val txt = "$hora · $msg"
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("estado", txt).apply()
        registrarHistorial(txt)
        getSystemService(NotificationManager::class.java).notify(1, notifEstado(txt))
    }

    private fun avisarReserva(c: JSONObject): String {
        val resumen = "${c.optString("fecha")} ${c.optString("hora")} - ${c.optString("nomModoVisita")}"
        val centro = c.optJSONObject("centroSalud")?.optString("nombre") ?: ""
        val medico = c.optString("nomProfesionalRealizador")
        val cuerpo = "$resumen\n$centro\n$medico\nNº solicitud: ${c.optString("numSolic")}"
        val n = Notification.Builder(this, CANAL_AVISO)
            .setSmallIcon(R.drawable.ic_notif_cita)
            .setContentTitle("¡CITA RESERVADA!")
            .setContentText(resumen)
            .setStyle(Notification.BigTextStyle().bigText(cuerpo))
            .setContentIntent(abrirApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(System.currentTimeMillis().toInt(), n)
        return resumen
    }

    private fun avisar(h: List<Hueco>) {
        val cuerpo = h.take(8).joinToString("\n") { it.texto } +
            if (h.size > 8) "\n… y ${h.size - 8} más" else ""
        val n = Notification.Builder(this, CANAL_AVISO)
            .setSmallIcon(R.drawable.ic_notif_cita)
            .setContentTitle("¡Cita SESCAM disponible!")
            .setContentText(h.first().texto)
            .setStyle(Notification.BigTextStyle().bigText(cuerpo))
            .setContentIntent(abrirApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(System.currentTimeMillis().toInt(), n)
    }
}
