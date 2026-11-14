package com.cita.sescam

import android.util.Base64
import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection

class Hueco(
    val fecha: LocalDate,
    val hora: String,
    val modo: String,
    val presencial: Boolean,
    val raw: JSONObject
) {
    /** Clave ordenable: 2026-11-23 18:38 */
    val clave: String get() = "$fecha $hora"
    val texto: String get() = "${fecha.format(FMT)} $hora - $modo"

    companion object {
        val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}

fun filtrar(todos: List<Hueco>, desde: LocalDate, hasta: LocalDate, modo: String): List<Hueco> =
    todos.filter {
        !it.fecha.isBefore(desde) && !it.fecha.isAfter(hasta) &&
            (modo == "ambos" || (modo == "presencial") == it.presencial)
    }

class SescamClient(private val cip: String) {
    private val base = "https://sescam.jccm.es/misaluddigital/citacion-primaria/api/v1/mi-salud-digital"
    private val pagina = "https://sescam.jccm.es/misaluddigital/app/citas/primaria/pasos-cita"
    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    private var token = ""
    private var exp = 0L
    private var cookiesListos = false

    init {
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(CookieManager())
    }

    private fun peticion(url: String, metodo: String, cuerpo: String? = null, auth: Boolean = false): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) {
            c.sslSocketFactory = Tls.fabrica
            c.hostnameVerifier = Tls.verificadorHost
        }
        try {
            c.requestMethod = metodo
            c.connectTimeout = 20000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            c.setRequestProperty("Accept-Language", "es-ES,es;q=0.9")
            c.setRequestProperty("Referer", pagina)
            if (auth) c.setRequestProperty("Authorization", "Bearer $token")
            if (cuerpo != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val texto = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, texto)
        } finally {
            c.disconnect()
        }
    }

    private fun jwtExp(t: String): Long = try {
        val p = t.split(".")[1]
        val json = String(Base64.decode(p, Base64.URL_SAFE or Base64.NO_WRAP))
        JSONObject(json).optLong("exp", 0L)
    } catch (e: Exception) {
        0L
    }

    private fun identificar() {
        if (!cookiesListos) {
            try { peticion(pagina, "GET") } catch (e: Exception) { }
            cookiesListos = true
        }
        val r = peticion("$base/identificarme", "POST", JSONObject().put("cip", cip).toString())
        if (r.first !in 200..299) throw RuntimeException("identificarme -> ${r.first}: ${r.second.take(200)}")
        var t = r.second.trim()
        if (t.startsWith("{")) {
            val o = JSONObject(t)
            t = listOf("token", "accessToken", "access_token", "jwt")
                .map { o.optString(it, "") }
                .firstOrNull { it.isNotEmpty() && it != "null" }
                ?: throw RuntimeException("No encuentro el token en la respuesta")
        }
        token = t.trim('"')
        exp = jwtExp(token)
    }

    private fun asegurarToken() {
        val ahora = System.currentTimeMillis() / 1000
        if (token.isEmpty() || ahora > exp - 30) identificar()
    }

    /** Reserva el hueco enviando el mismo objeto que devolvio la lista + codPersoReg. */
    fun reservar(h: Hueco): JSONObject {
        asegurarToken()
        val cuerpo = JSONObject(h.raw.toString()).put("codPersoReg", "MSDWEB").toString()
        val url = "$base/pacientes/$cip/citas"
        var r = peticion(url, "POST", cuerpo, auth = true)
        if (r.first == 401 || r.first == 403) {
            identificar()
            r = peticion(url, "POST", cuerpo, auth = true)
        }
        if (r.first !in 200..299) throw RuntimeException("reservar -> ${r.first}: ${r.second.take(200)}")
        return JSONObject(r.second)
    }

    fun huecos(tramite: String = "MEDDE"): List<Hueco> {
        asegurarToken()
        val url = "$base/pacientes/$cip/tramites/$tramite/huecos"
        var r = peticion(url, "GET", auth = true)
        if (r.first == 401 || r.first == 403) {
            identificar()
            r = peticion(url, "GET", auth = true)
        }
        if (r.first !in 200..299) throw RuntimeException("huecos -> ${r.first}: ${r.second.take(200)}")
        return parsear(r.second)
    }

    private fun fecha(s: String): LocalDate? = try {
        LocalDate.parse(s.take(10), Hueco.FMT)
    } catch (e: Exception) {
        try { LocalDate.parse(s.take(10)) } catch (e2: Exception) { null }
    }

    private fun parsear(texto: String): List<Hueco> {
        val data = JSONObject(texto)
        val out = ArrayList<Hueco>()
        val agendas = data.optJSONArray("agendas") ?: return out
        for (i in 0 until agendas.length()) {
            val hs = agendas.getJSONObject(i).optJSONArray("huecos") ?: continue
            for (j in 0 until hs.length()) {
                val h = hs.getJSONObject(j)
                val f = fecha(h.optString("fecha", "")) ?: continue
                val modo = h.optString("nomModoVisita", "")
                val pres = h.optInt("codModoVisita", -1) == 1 || modo.contains("presencial", ignoreCase = true)
                out.add(Hueco(f, h.optString("hora", ""), modo, pres, h))
            }
        }
        return out.sortedBy { it.clave }
    }
}
