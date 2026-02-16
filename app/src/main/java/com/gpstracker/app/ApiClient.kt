package com.gpstracker.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

object ApiClient {

    // TODO: Alterar para o endereço do seu servidor
    private const val API_URL = "https://tech7.pedron.com.br/api/sync_locations.php"

    // ⚠️ ATENÇÃO: isso desliga a validação SSL (use só em teste!)
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

    fun syncLocations(locations: List<GpsLocation>): Boolean {
        try {
            // Ignorar SSL inválido (expirado, autoassinado, domínio diferente)
            disableSSLVerification()

            val jsonArray = JSONArray()

            for (location in locations) {
                val jsonObject = JSONObject().apply {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("altitude", location.altitude)
                    put("accuracy", location.accuracy)
                    put("speed", location.speed)
                    put("bearing", location.bearing)
                    put("timestamp", location.timestamp)
                }
                jsonArray.put(jsonObject)
            }

            val url = URL(API_URL)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
            }

            // Enviar dados
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonArray.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    reader.readText()
                }

                val jsonResponse = JSONObject(response)
                return jsonResponse.optBoolean("success", false)
            }

            connection.disconnect()

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        return false
    }
}
