package com.gpstracker.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
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
    private lateinit var locationManager: LocationManager
    private lateinit var database: GpsDatabase
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Monitoramento de satélites
    private var satellitesUsed = 0
    private var satellitesVisible = 0
    private var gnssCallback: GnssStatus.Callback? = null

    // Watchdog: verifica se GPS continua ativo
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogIntervalMs = 5 * 60 * 1000L
    private val maxSilenceMs      = 10 * 60 * 1000L

    // Heartbeat: força ponto GPS baseado no perfil ativo
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatIntervalMs: Long get() {
        val p = ConfigActivity.getLocationParams(ConfigActivity.getProfile(this))
        return (p.intervalMs * 2).coerceIn(30_000L, 5 * 60_000L)
    }

    companion object {
        private const val CHANNEL_ID   = "gps_tracking_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_UPDATE_UI = "com.gpstracker.app.UPDATE_UI"

        var isRunning: Boolean = false
        var lastLocationTime: Long = 0L
        var lastLocationCount: Int = 0
        var watchdogResetCount: Int = 0

        // Informações de satélites e sinal (visível pela MainActivity)
        var satellites: Int = 0
        var satellitesInUse: Int = 0
        var signalQuality: String = "Aguardando GPS..."
        var lastAccuracy: Float = -1f
    }

    // ------------------------------------------------------------------ //
    //  CICLO DE VIDA                                                       //
    // ------------------------------------------------------------------ //

    override fun onCreate() {
        super.onCreate()
        database = GpsDatabase(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        createNotificationChannel()
        createLocationCallback()
        startSatelliteMonitoring()

        startForeground(NOTIFICATION_ID, buildNotification("Iniciando GPS..."))
        requestLocationUpdates()
        startHeartbeat()
        startWatchdog()

        isRunning = true
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        stopSatelliteMonitoring()
        watchdogHandler.removeCallbacksAndMessages(null)
        heartbeatHandler.removeCallbacksAndMessages(null)
        isRunning = false
        lastLocationTime = 0L
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
    //  MONITORAMENTO DE SATÉLITES                                          //
    // ------------------------------------------------------------------ //

    private fun startSatelliteMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return

            gnssCallback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    satellitesVisible = status.satelliteCount
                    satellitesUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }

                    satellites = satellitesVisible
                    satellitesInUse = satellitesUsed

                    // Atualiza qualidade do sinal
                    signalQuality = when {
                        satellitesUsed == 0  -> "Sem GPS"
                        satellitesUsed < 4   -> "GPS Fraco (${satellitesUsed} sats)"
                        satellitesUsed < 8   -> "GPS Bom (${satellitesUsed} sats)"
                        else                 -> "GPS Excelente (${satellitesUsed} sats)"
                    }

                    sendBroadcast(Intent(ACTION_UPDATE_UI))
                }
            }
            locationManager.registerGnssStatusCallback(gnssCallback!!, Handler(Looper.getMainLooper()))
        }
    }

    private fun stopSatelliteMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssCallback?.let { locationManager.unregisterGnssStatusCallback(it) }
        }
    }

    // ------------------------------------------------------------------ //
    //  GPS                                                                 //
    // ------------------------------------------------------------------ //

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                for (location in locationResult.locations) {
                    processLocation(location)
                }
            }
        }
    }

    private fun requestLocationUpdates() {
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
    //  PROCESSAMENTO DE LOCALIZAÇÕES (com filtros relaxados)              //
    // ------------------------------------------------------------------ //

    private fun processLocation(location: Location) {
        val acc = location.accuracy
        lastAccuracy = acc

        // Log da tentativa
        val attemptStatus: String
        val attemptReason: String

        when {
            // Precisão excelente — salva sempre
            acc < 30f -> {
                saveLocation(location)
                attemptStatus = "success"
                attemptReason = "Precisão excelente (${acc.toInt()}m)"
            }

            // Precisão boa — salva com aviso
            acc in 30f..100f -> {
                saveLocation(location)
                attemptStatus = "success"
                attemptReason = "Precisão aceitável (${acc.toInt()}m)"
            }

            // Precisão ruim — salva só se passou >5min sem nada (emergência)
            acc > 100f -> {
                val elapsed = System.currentTimeMillis() - lastLocationTime
                if (elapsed > 5 * 60_000L) {
                    saveLocation(location)
                    attemptStatus = "poor_accuracy"
                    attemptReason = "Precisão baixa (${acc.toInt()}m), mas >5min sem registro"
                } else {
                    attemptStatus = "poor_accuracy"
                    attemptReason = "Precisão muito baixa (${acc.toInt()}m), descartado"
                }
            }

            else -> {
                attemptStatus = "no_signal"
                attemptReason = "GPS sem sinal válido"
            }
        }

        // Registra tentativa no log
        serviceScope.launch {
            database.insertAttempt(GpsAttempt(
                timestamp  = System.currentTimeMillis(),
                satellites = satellitesUsed,
                accuracy   = acc,
                status     = attemptStatus,
                reason     = attemptReason
            ))
        }
    }

    // ------------------------------------------------------------------ //
    //  HEARTBEAT — grava ponto forçado periodicamente                      //
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
                    if (System.currentTimeMillis() - lastLocationTime >= 60_000L) {
                        processLocation(it)
                    }
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    // ------------------------------------------------------------------ //
    //  WATCHDOG — detecta GPS congelado                                    //
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
        if (lastLocationTime == 0L) return

        val silenceMs = System.currentTimeMillis() - lastLocationTime

        // Se passou muito tempo sem GPS E tem poucos satélites → reinicia
        if (silenceMs > maxSilenceMs && satellitesUsed < 4) {
            restartLocationUpdates()

            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val lastStr = fmt.format(Date(lastLocationTime))
            updateNotification("⚠ GPS reiniciado ($watchdogResetCount×). Último: $lastStr")

            // Log da tentativa de recuperação
            serviceScope.launch {
                database.insertAttempt(GpsAttempt(
                    timestamp  = System.currentTimeMillis(),
                    satellites = satellitesUsed,
                    accuracy   = lastAccuracy,
                    status     = "timeout",
                    reason     = "Sem registro por ${silenceMs/60_000}min, ${satellitesUsed} sats — GPS reiniciado"
                ))
            }

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
        val battery = getBatteryLevel()
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

            lastLocationTime  = location.time
            lastLocationCount++

            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            updateNotification(
                "Último: ${fmt.format(Date(location.time))} | Total: $lastLocationCount"
            )

            sendBroadcast(Intent(ACTION_UPDATE_UI))
        }
    }
}
