package com.gpstracker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GpsTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var database: GpsDatabase
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Watchdog: verifica se GPS continua ativo
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogIntervalMs = 5 * 60 * 1000L  // Verifica a cada 5 minutos
    private val maxSilenceMs      = 10 * 60 * 1000L  // Máximo 10 min sem registro

    // Heartbeat: força ponto GPS baseado no perfil ativo
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    // Usa o dobro do intervalo do perfil como heartbeat (mín 30s, máx 5min)
    private val heartbeatIntervalMs: Long get() {
        val p = ConfigActivity.getLocationParams(ConfigActivity.getProfile(this))
        return (p.intervalMs * 2).coerceIn(30_000L, 5 * 60_000L)
    }

    companion object {
        private const val CHANNEL_ID   = "gps_tracking_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_UPDATE_UI = "com.gpstracker.app.UPDATE_UI"

        var isRunning: Boolean = false
        var lastLocationTime: Long = 0L   // timestamp do último ponto gravado
        var lastLocationCount: Int = 0    // contagem atualizada pelo serviço
        var watchdogResetCount: Int = 0   // quantas vezes o watchdog reiniciou
    }

    // ------------------------------------------------------------------ //
    //  CICLO DE VIDA                                                       //
    // ------------------------------------------------------------------ //

    override fun onCreate() {
        super.onCreate()
        database = GpsDatabase(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()
        createLocationCallback()

        startForeground(NOTIFICATION_ID, buildNotification("Iniciando GPS..."))
        requestLocationUpdates()
        startHeartbeat()
        startWatchdog()

        isRunning = true
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        watchdogHandler.removeCallbacksAndMessages(null)
        heartbeatHandler.removeCallbacksAndMessages(null)
        isRunning = false
        lastLocationTime = 0L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Se o sistema matar o serviço, ele se reinicia automaticamente
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // ------------------------------------------------------------------ //
    //  NOTIFICAÇÃO                                                         //
    // ------------------------------------------------------------------ //

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rastreamento GPS",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Rastreamento de localização em tempo real" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Tracker")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    // ------------------------------------------------------------------ //
    //  GPS                                                                 //
    // ------------------------------------------------------------------ //

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                for (location in locationResult.locations) {
                    saveLocation(location)
                }
            }
        }
    }

    private fun requestLocationUpdates() {
        // Lê o perfil escolhido pelo usuário nas Configurações
        val p = ConfigActivity.getLocationParams(ConfigActivity.getProfile(this))

        val locationRequest = LocationRequest.Builder(p.priority, p.intervalMs).apply {
            setMinUpdateIntervalMillis(p.minIntervalMs)
            setMinUpdateDistanceMeters(p.minDistanceM)
            setWaitForAccurateLocation(false)
            setMaxUpdateDelayMillis(p.maxDelayMs)
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest, locationCallback, Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun restartLocationUpdates() {
        stopLocationUpdates()
        requestLocationUpdates()
        watchdogResetCount++
    }

    // ------------------------------------------------------------------ //
    //  HEARTBEAT — grava ponto a cada 3 min mesmo sem movimentação        //
    // ------------------------------------------------------------------ //

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            forceCurrentLocation()
            heartbeatHandler.postDelayed(this, heartbeatIntervalMs)
        }
    }

    private fun startHeartbeat() {
        heartbeatHandler.postDelayed(heartbeatRunnable, heartbeatIntervalMs)
    }

    private fun forceCurrentLocation() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    // Só grava se passou ao menos 60 s do último ponto
                    if (System.currentTimeMillis() - lastLocationTime >= 60_000L) {
                        saveLocation(it)
                    }
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    // ------------------------------------------------------------------ //
    //  WATCHDOG — detecta e cura congelamento do GPS                      //
    // ------------------------------------------------------------------ //

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkAndHeal()
            watchdogHandler.postDelayed(this, watchdogIntervalMs)
        }
    }

    private fun startWatchdog() {
        watchdogHandler.postDelayed(watchdogRunnable, watchdogIntervalMs)
    }

    private fun checkAndHeal() {
        // Ignora as primeiras verificações enquanto GPS ainda está "esquentando"
        if (lastLocationTime == 0L) return

        val silenceMs = System.currentTimeMillis() - lastLocationTime

        if (silenceMs > maxSilenceMs) {
            // GPS congelou → reinicia as atualizações
            restartLocationUpdates()

            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val lastStr = fmt.format(Date(lastLocationTime))
            updateNotification("⚠ GPS reiniciado ($watchdogResetCount×). Último: $lastStr")

            // Notifica a MainActivity para atualizar a tela
            sendBroadcast(Intent(ACTION_UPDATE_UI))
        }
    }

    // ------------------------------------------------------------------ //
    //  SALVAR LOCALIZAÇÃO                                                  //
    // ------------------------------------------------------------------ //

    private fun getBatteryLevel(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    private fun saveLocation(location: Location) {
        val battery = getBatteryLevel()   // lê antes de entrar na coroutine
        serviceScope.launch {
            val gpsLocation = GpsLocation(
                latitude  = location.latitude,
                longitude = location.longitude,
                altitude  = location.altitude,
                accuracy  = location.accuracy,
                speed     = location.speed,
                bearing   = location.bearing,
                timestamp = location.time,
                battery   = battery
            )
            database.insertLocation(gpsLocation)

            // Atualiza estado compartilhado com a MainActivity
            lastLocationTime  = location.time
            lastLocationCount++

            // Atualiza notificação com hora do último ponto
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            updateNotification(
                "Último: ${fmt.format(Date(location.time))} | Total: $lastLocationCount"
            )

            // Dispara broadcast para MainActivity atualizar a tela imediatamente
            sendBroadcast(Intent(ACTION_UPDATE_UI))
        }
    }
}
