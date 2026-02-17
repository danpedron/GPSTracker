package com.gpstracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object ApiClient {

    private const val API_URL = "https://tech7.pedron.com.br/api/sync_locations.php"

    // Ignorar SSL inválido (expirado, autoassinado, domínio diferente)
    private fun disableSSLVerification() {
        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
    }

    fun syncLocations(context: Context, locations: List<GpsLocation>): Boolean {
        try {
            disableSSLVerification()

            // Pega o device_id configurado pelo usuário
            val deviceId = ConfigActivity.getDeviceId(context)

            val jsonArray = JSONArray()
            for (location in locations) {
                val obj = JSONObject().apply {
                    put("device_id",  deviceId)
                    put("latitude",   location.latitude)
                    put("longitude",  location.longitude)
                    put("altitude",   location.altitude)
                    put("accuracy",   location.accuracy)
                    put("speed",      location.speed)
                    put("bearing",    location.bearing)
                    put("timestamp",  location.timestamp)
                    put("battery",    location.battery)
                }
                jsonArray.put(obj)
            }

            val url        = URL(API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput       = true
                connectTimeout = 15000
                readTimeout    = 15000
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonArray.toString())
                writer.flush()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream))
                    .use { it.readText() }
                return JSONObject(response).optBoolean("success", false)
            }

            connection.disconnect()

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return false
    }
}
