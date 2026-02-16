package com.gpstracker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GpsTrackingService : Service() {
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var database: GpsDatabase
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    companion object {
        private const val CHANNEL_ID = "gps_tracking_channel"
        private const val NOTIFICATION_ID = 1001
        var isRunning = false
    }
    
    override fun onCreate() {
        super.onCreate()
        
        database = GpsDatabase(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        createLocationCallback()
        createNotificationChannel()
        
        startForeground(NOTIFICATION_ID, createNotification())
        requestLocationUpdates()
        
        isRunning = true
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        isRunning = false
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rastreamento GPS",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Rastreamento de localização em tempo real"
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            intent, 
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Tracker")
            .setContentText("Rastreando sua localização...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
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
        /*
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L // Atualização a cada 5 segundos
        ).apply {
            setMinUpdateIntervalMillis(2000L) // Mínimo 2 segundos
            setMinUpdateDistanceMeters(5f) // Mínimo 5 metros de distância
            setWaitForAccurateLocation(false)
        }.build() */
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            30000L  // 30 segundos (120 pontos/hora)
        ).apply {
            setMinUpdateIntervalMillis(15000L)  // 15 seg mínimo
            setMinUpdateDistanceMeters(20f)     // 20 metros
        }.build()
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
    
    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
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
                timestamp = location.time
            )
            
            database.insertLocation(gpsLocation)
        }
    }
}
