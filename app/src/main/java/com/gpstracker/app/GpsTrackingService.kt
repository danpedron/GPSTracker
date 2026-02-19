package com.gpstracker.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class GpsTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationManager: LocationManager
    private lateinit var database: GpsDatabase

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var satellitesUsed = 0
    private var satellitesVisible = 0
    private var gnssCallback: GnssStatus.Callback? = null
    private var gnssThread: HandlerThread? = null

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val watchdogIntervalMs = 5 * 60 * 1000L
    private val maxSilenceMs = 10 * 60 * 1000L

    private var lastUiBroadcastTime = 0L
    private var lastNotificationUpdate = 0L

    companion object {
        private const val CHANNEL_ID = "gps_tracking_channel"
        private const val NOTIFICATION_ID = 1001
        private const val UI_BROADCAST_INTERVAL = 1000L
        private const val NOTIFICATION_INTERVAL = 60_000L

        const val ACTION_UPDATE_UI = "com.gpstracker.app.UPDATE_UI"

        var isRunning = false
        var lastLocationTime = 0L
        var lastLocationCount = 0
        var watchdogResetCount = 0

        var satellites = 0
        var satellitesInUse = 0
        var signalQuality = "Aguardando GPS..."
        var lastAccuracy: Float = -1f
    }

    override fun onCreate() {
        super.onCreate()

        database = GpsDatabase.getInstance(this)
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
        serviceScope.cancel()
        isRunning = false
        lastLocationTime = 0L
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ----------------------------------------------------
    // NOTIFICAÇÃO
    // ----------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rastreamento GPS",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Tracker")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdate < NOTIFICATION_INTERVAL) return
        lastNotificationUpdate = now

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    // ----------------------------------------------------
    // THROTTLED UI BROADCAST
    // ----------------------------------------------------

    private fun safeBroadcastUI() {
        val now = System.currentTimeMillis()
        if (now - lastUiBroadcastTime > UI_BROADCAST_INTERVAL) {
            lastUiBroadcastTime = now
            sendBroadcast(Intent(ACTION_UPDATE_UI))
        }
    }

    // ----------------------------------------------------
    // SATÉLITES
    // ----------------------------------------------------

    private fun startSatelliteMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {

            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) return

            gnssThread = HandlerThread("GnssThread")
            gnssThread!!.start()

            gnssCallback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    satellitesVisible = status.satelliteCount
                    satellitesUsed =
                        (0 until status.satelliteCount).count { status.usedInFix(it) }

                    satellites = satellitesVisible
                    satellitesInUse = satellitesUsed

                    signalQuality = when {
                        satellitesUsed == 0 -> "Sem GPS"
                        satellitesUsed < 4 -> "GPS Fraco ($satellitesUsed)"
                        satellitesUsed < 8 -> "GPS Bom ($satellitesUsed)"
                        else -> "GPS Excelente ($satellitesUsed)"
                    }

                    safeBroadcastUI()
                }
            }

            locationManager.registerGnssStatusCallback(
                gnssCallback!!,
                Handler(gnssThread!!.looper)
            )
        }
    }

    private fun stopSatelliteMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssCallback?.let {
                locationManager.unregisterGnssStatusCallback(it)
            }
            gnssThread?.quitSafely()
        }
    }

    // ----------------------------------------------------
    // GPS
    // ----------------------------------------------------

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    processLocation(location)
                }
            }
        }
    }

    private fun requestLocationUpdates() {
        val p = ConfigActivity.getLocationParams(ConfigActivity.getProfile(this))

        val request = LocationRequest.Builder(p.priority, p.intervalMs).apply {
            setMinUpdateIntervalMillis(p.minIntervalMs)
            setMinUpdateDistanceMeters(p.minDistanceM)
            setWaitForAccurateLocation(false)
            setMaxUpdateDelayMillis(p.maxDelayMs)
        }.build()

        fusedLocationClient.requestLocationUpdates(
            request, locationCallback, Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun restartLocationUpdates() {
        stopLocationUpdates()
        requestLocationUpdates()
        watchdogResetCount++
    }

    // ----------------------------------------------------
    // PROCESSAMENTO
    // ----------------------------------------------------

    private fun processLocation(location: Location) {
        val acc = location.accuracy
        lastAccuracy = acc

        if (acc < 100f ||
            System.currentTimeMillis() - lastLocationTime > 5 * 60_000L
        ) {
            saveLocation(location)
        }
    }

    private fun saveLocation(location: Location) {
        serviceScope.launch {
            val gpsLocation = GpsLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                accuracy = location.accuracy,
                speed = location.speed,
                bearing = location.bearing,
                timestamp = location.time,
                battery = getBatteryLevel()
            )

            database.insertLocation(gpsLocation)

            lastLocationTime = location.time
            lastLocationCount++

            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            updateNotification(
                "Último: ${fmt.format(Date(location.time))} | Total: $lastLocationCount"
            )

            safeBroadcastUI()
        }
    }

    // ----------------------------------------------------
    // HEARTBEAT
    // ----------------------------------------------------

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            fusedLocationClient.lastLocation.addOnSuccessListener {
                it?.let { loc ->
                    if (System.currentTimeMillis() - lastLocationTime >= 60_000L) {
                        processLocation(loc)
                    }
                }
            }
            heartbeatHandler.postDelayed(this, 60_000L)
        }
    }

    private fun startHeartbeat() {
        heartbeatHandler.postDelayed(heartbeatRunnable, 60_000L)
    }

    // ----------------------------------------------------
    // WATCHDOG
    // ----------------------------------------------------

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val silence = System.currentTimeMillis() - lastLocationTime

            if (lastLocationTime > 0 &&
                silence > maxSilenceMs &&
                satellitesUsed < 4
            ) {
                restartLocationUpdates()
                updateNotification("⚠ GPS reiniciado ($watchdogResetCount×)")
                safeBroadcastUI()
            }

            watchdogHandler.postDelayed(this, watchdogIntervalMs)
        }
    }

    private fun startWatchdog() {
        watchdogHandler.postDelayed(watchdogRunnable, watchdogIntervalMs)
    }

    // ----------------------------------------------------
    // BATERIA
    // ----------------------------------------------------

    private fun getBatteryLevel(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }
}
