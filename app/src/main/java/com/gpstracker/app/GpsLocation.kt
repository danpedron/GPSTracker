package com.gpstracker.app

data class GpsLocation(
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val timestamp: Long,
    val battery: Int = -1,    // percentual de bateria (0-100), -1 = não disponível
    val synced: Boolean = false
)
