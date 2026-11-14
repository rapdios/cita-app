package com.cita.sescam

import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Muchos servidores no envian el certificado intermedio. Chrome lo descarga solo,
 * pero Android no. Aqui, si la validacion normal falla, descargamos el intermedio
 * desde la direccion que indica el propio certificado (AIA) y volvemos a validar
 * contra las autoridades de confianza del movil. NO se desactiva ninguna comprobacion.
 */
object Tls {

    val fabrica: SSLSocketFactory by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val sistema = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()

        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                sistema.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    sistema.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    val completa = completar(chain) ?: throw e
                    sistema.checkServerTrusted(completa, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = sistema.acceptedIssuers
        }

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        ctx.socketFactory
    }

    private val cache = HashMap<String, X509Certificate?>()

    private fun completar(chain: Array<X509Certificate>): Array<X509Certificate>? {
        val lista = ArrayList<X509Certificate>(chain.toList())
        var actual = lista.last()
        for (i in 0 until 4) {
            if (actual.subjectX500Principal == actual.issuerX500Principal) break
            val emisor = urlsAia(actual).firstNotNullOfOrNull { descargar(it) } ?: break
            lista.add(emisor)
            actual = emisor
        }
        return if (lista.size > chain.size) lista.toTypedArray() else null
    }

    private fun descargar(url: String): X509Certificate? {
        cache[url]?.let { return it }
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 15000
            val cert = c.inputStream.use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
            cache[url] = cert
            cert
        } catch (e: Exception) {
            null
        }
    }

    private class Tlv(val tag: Int, val ini: Int, val fin: Int)

    private fun tlv(b: ByteArray, p: Int): Tlv {
        val tag = b[p].toInt() and 0xff
        var l = b[p + 1].toInt() and 0xff
        var i = p + 2
        if (l and 0x80 != 0) {
            val n = l and 0x7f
            l = 0
            for (k in 0 until n) {
                l = (l shl 8) or (b[i].toInt() and 0xff)
                i++
            }
        }
        return Tlv(tag, i, i + l)
    }

    /** Direcciones "CA Issuers" de la extension Authority Information Access. */
    private fun urlsAia(cert: X509Certificate): List<String> {
        val ext = cert.getExtensionValue("1.3.6.1.5.5.7.1.1") ?: return emptyList()
        val out = ArrayList<String>()
        try {
            val octeto = tlv(ext, 0)
            val seq = tlv(ext, octeto.ini)
            var p = seq.ini
            while (p < seq.fin) {
                val ad = tlv(ext, p)
                val oid = tlv(ext, ad.ini)
                val loc = tlv(ext, oid.fin)
                val esCaIssuers = (oid.fin - oid.ini == 8) && ext[oid.fin - 1].toInt() == 2
                if (esCaIssuers && loc.tag == 0x86) {
                    out.add(String(ext, loc.ini, loc.fin - loc.ini, Charsets.US_ASCII))
                }
                p = ad.fin
            }
        } catch (e: Exception) {
        }
        return out
    }
}
