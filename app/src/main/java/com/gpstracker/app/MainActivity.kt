package com.gpstracker.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    
    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var syncButton: Button
    private lateinit var clearButton: Button
    private lateinit var autoStartCheckbox: CheckBox
    
    private lateinit var database: GpsDatabase
    
    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        database = GpsDatabase(this)
        
        // Inicializar views
        statusText = findViewById(R.id.statusText)
        countText = findViewById(R.id.countText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        syncButton = findViewById(R.id.syncButton)
        clearButton = findViewById(R.id.clearButton)
        autoStartCheckbox = findViewById(R.id.autoStartCheckbox)
        
        // Configurar checkbox de auto-start
        autoStartCheckbox.isChecked = BootReceiver.PrefsHelper.isAutoStartEnabled(this)
        autoStartCheckbox.setOnCheckedChangeListener { _, isChecked ->
            BootReceiver.PrefsHelper.setAutoStartEnabled(this, isChecked)
            val message = if (isChecked) {
                "Auto-iniciar ativado. O rastreamento continuará após reiniciar o celular."
            } else {
                "Auto-iniciar desativado."
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        
        // Configurar botões
        startButton.setOnClickListener { startTracking() }
        stopButton.setOnClickListener { stopTracking() }
        syncButton.setOnClickListener { syncData() }
        clearButton.setOnClickListener { clearData() }
        
        // Verificar permissões
        checkPermissions()
        
        // Atualizar UI
        updateUI()
    }
    
    override fun onResume() {
        super.onResume()
        updateUI()
    }
    
    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        // Adicionar permissão de BOOT
        permissions.add(Manifest.permission.RECEIVE_BOOT_COMPLETED)
        
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), LOCATION_PERMISSION_REQUEST)
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissões concedidas", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permissões necessárias para rastreamento", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun startTracking() {
        val serviceIntent = Intent(this, GpsTrackingService::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        // Salvar que está rastreando (para auto-start)
        BootReceiver.PrefsHelper.setWasTracking(this, true)
        
        Toast.makeText(this, "Rastreamento iniciado", Toast.LENGTH_SHORT).show()
        updateUI()
    }
    
    private fun stopTracking() {
        val serviceIntent = Intent(this, GpsTrackingService::class.java)
        stopService(serviceIntent)
        
        // Salvar que NÃO está rastreando
        BootReceiver.PrefsHelper.setWasTracking(this, false)
        
        Toast.makeText(this, "Rastreamento parado", Toast.LENGTH_SHORT).show()
        updateUI()
    }
    
    private fun updateUI() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                database.getLocationCount()
            }
            
            countText.text = "Localizações registradas: $count"
            
            val isRunning = GpsTrackingService.isRunning
            statusText.text = if (isRunning) "Status: Rastreando" else "Status: Parado"
            
            startButton.isEnabled = !isRunning
            stopButton.isEnabled = isRunning
        }
    }
    
    private fun syncData() {
        lifecycleScope.launch {
            val locations = withContext(Dispatchers.IO) {
                database.getAllLocations()
            }
            
            if (locations.isEmpty()) {
                Toast.makeText(this@MainActivity, "Nenhum dado para sincronizar", Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            Toast.makeText(this@MainActivity, "Sincronizando ${locations.size} localizações...", Toast.LENGTH_SHORT).show()
            
            val success = withContext(Dispatchers.IO) {
                ApiClient.syncLocations(locations)
            }
            
            if (success) {
                withContext(Dispatchers.IO) {
                    database.clearLocations()
                }
                Toast.makeText(this@MainActivity, "Dados sincronizados com sucesso!", Toast.LENGTH_SHORT).show()
                updateUI()
            } else {
                Toast.makeText(this@MainActivity, "Erro ao sincronizar. Tente novamente.", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun clearData() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.clearLocations()
            }
            Toast.makeText(this@MainActivity, "Dados apagados", Toast.LENGTH_SHORT).show()
            updateUI()
        }
    }
}
