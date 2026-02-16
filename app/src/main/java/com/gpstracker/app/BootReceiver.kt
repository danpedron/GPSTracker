package com.gpstracker.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val PREFS_NAME = "gps_tracker_prefs"
        private const val KEY_AUTO_START = "auto_start_enabled"
        private const val KEY_WAS_TRACKING = "was_tracking_before_shutdown"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            // Verificar se auto-start está habilitado
            val autoStartEnabled = prefs.getBoolean(KEY_AUTO_START, false)
            
            // Verificar se estava rastreando antes de desligar
            val wasTracking = prefs.getBoolean(KEY_WAS_TRACKING, false)
            
            if (autoStartEnabled && wasTracking) {
                // Iniciar serviço de rastreamento
                val serviceIntent = Intent(context, GpsTrackingService::class.java)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
    
    // Funções helper para gerenciar preferências
    object PrefsHelper {
        fun setAutoStartEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_START, enabled)
                .apply()
        }
        
        fun isAutoStartEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START, false)
        }
        
        fun setWasTracking(context: Context, tracking: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WAS_TRACKING, tracking)
                .apply()
        }
    }
}
