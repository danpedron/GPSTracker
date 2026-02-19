package com.gpstracker.app

data class GpsAttempt(
    val id: Long = 0,
    val timestamp: Long,
    val satellites: Int,
    val accuracy: Float,
    val status: String,       // "success", "no_signal", "poor_accuracy", "timeout"
    val reason: String = ""   // Descrição detalhada do problema
)
