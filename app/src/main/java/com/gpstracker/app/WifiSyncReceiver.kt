package com.gpstracker.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WifiSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Só age em eventos de conectividade
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION &&
            intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return

        // Verifica se está conectado em WiFi agora
        val ssid = getCurrentWifiSsid(context) ?: return

        // Verifica se este SSID está na lista de redes configuradas
        val trustedNetworks = ConfigActivity.getTrustedNetworks(context)
        if (trustedNetworks.isEmpty()) return
        if (!trustedNetworks.any { it.equals(ssid, ignoreCase = true) }) return

        // Está em uma rede confiável — sincroniza se houver dados
        CoroutineScope(Dispatchers.IO).launch {
            val db        = GpsDatabase(context)
            val locations = db.getAllLocations()
            if (locations.isEmpty()) return@launch

            val success = ApiClient.syncLocations(context, locations)
            if (success) {
                db.clearLocations()
                GpsTrackingService.lastLocationCount = 0

                // Notificação de sucesso
                showNotification(
                    context,
                    "GPS Tracker — Sincronização automática",
                    "✅ ${locations.size} pontos enviados via WiFi \"$ssid\""
                )
            }
            // Se falhou, não apaga nada — dados ficam seguros para próxima tentativa
        }
    }

    private fun getCurrentWifiSsid(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

            if (!wifiManager.isWifiEnabled) return null

            val info = wifiManager.connectionInfo ?: return null
            val ssid = info.ssid ?: return null

            // Android retorna SSID entre aspas: "GNET_ESTRELA" → removemos
            ssid.trim().removeSurrounding("\"")
                .takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        } catch (e: Exception) {
            null
        }
    }

    private fun showNotification(context: Context, title: String, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager

        // Cria canal se necessário
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID, "Sincronização WiFi",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_WIFI_SYNC, notification)
    }

    companion object {
        private const val CHANNEL_ID          = "wifi_sync_channel"
        private const val NOTIFICATION_WIFI_SYNC = 1002
    }
}
