package com.gpstracker.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var lastLocationText: TextView   // NOVO: data/hora último ponto
    private lateinit var watchdogText: TextView       // NOVO: info do watchdog
    private lateinit var countText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var syncButton: Button
    private lateinit var clearButton: Button
    private lateinit var autoStartCheckbox: CheckBox

    private lateinit var database: GpsDatabase

    // Recebe broadcasts do serviço para atualizar a tela
    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUI()
        }
    }

    // Atualiza a tela a cada 30 segundos mesmo sem broadcast
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateUI()
            refreshHandler.postDelayed(this, 30_000L)
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
    }

    // ------------------------------------------------------------------ //
    //  CICLO DE VIDA                                                       //
    // ------------------------------------------------------------------ //

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        database = GpsDatabase(this)

        statusText       = findViewById(R.id.statusText)
        lastLocationText = findViewById(R.id.lastLocationText)
        watchdogText     = findViewById(R.id.watchdogText)
        countText        = findViewById(R.id.countText)
        startButton      = findViewById(R.id.startButton)
        stopButton       = findViewById(R.id.stopButton)
        syncButton       = findViewById(R.id.syncButton)
        clearButton      = findViewById(R.id.clearButton)
        autoStartCheckbox = findViewById(R.id.autoStartCheckbox)

        autoStartCheckbox.isChecked = BootReceiver.PrefsHelper.isAutoStartEnabled(this)
        autoStartCheckbox.setOnCheckedChangeListener { _, isChecked ->
            BootReceiver.PrefsHelper.setAutoStartEnabled(this, isChecked)
            val msg = if (isChecked)
                "Auto-iniciar ativado."
            else
                "Auto-iniciar desativado."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        startButton.setOnClickListener { startTracking() }
        stopButton.setOnClickListener  { stopTracking() }
        syncButton.setOnClickListener  { syncData() }
        clearButton.setOnClickListener { clearData() }

        checkPermissions()
        updateUI()
    }

    override fun onResume() {
        super.onResume()

        // Registrar receiver para atualizações do serviço
        val filter = IntentFilter(GpsTrackingService.ACTION_UPDATE_UI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiUpdateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiUpdateReceiver, filter)
        }

        // Iniciar refresh periódico enquanto app está aberto
        refreshHandler.post(refreshRunnable)

        updateUI()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(uiUpdateReceiver)
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    // ------------------------------------------------------------------ //
    //  PERMISSÕES                                                          //
    // ------------------------------------------------------------------ //

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty())
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), LOCATION_PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                Toast.makeText(this, "Permissões concedidas", Toast.LENGTH_SHORT).show()
            else
                Toast.makeText(this, "Permissões necessárias para rastreamento", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ //
    //  CONTROLES                                                           //
    // ------------------------------------------------------------------ //

    private fun startTracking() {
        val serviceIntent = Intent(this, GpsTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(serviceIntent)
        else
            startService(serviceIntent)

        BootReceiver.PrefsHelper.setWasTracking(this, true)
        Toast.makeText(this, "Rastreamento iniciado", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun stopTracking() {
        stopService(Intent(this, GpsTrackingService::class.java))
        BootReceiver.PrefsHelper.setWasTracking(this, false)
        Toast.makeText(this, "Rastreamento parado", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun syncData() {
        lifecycleScope.launch {
            val locations = withContext(Dispatchers.IO) { database.getAllLocations() }

            if (locations.isEmpty()) {
                Toast.makeText(this@MainActivity, "Nenhum dado para sincronizar", Toast.LENGTH_SHORT).show()
                return@launch
            }

            Toast.makeText(this@MainActivity, "Sincronizando ${locations.size} pontos...", Toast.LENGTH_SHORT).show()

            val success = withContext(Dispatchers.IO) { ApiClient.syncLocations(this@MainActivity, locations) }

            if (success) {
                withContext(Dispatchers.IO) { database.clearLocations() }
                // Zera contagem no serviço também
                GpsTrackingService.lastLocationCount = 0
                Toast.makeText(this@MainActivity, "Sincronizado com sucesso!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Erro ao sincronizar. Tente novamente.", Toast.LENGTH_LONG).show()
            }
            updateUI()
        }
    }

    private fun clearData() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { database.clearLocations() }
            GpsTrackingService.lastLocationCount = 0
            Toast.makeText(this@MainActivity, "Dados apagados", Toast.LENGTH_SHORT).show()
            updateUI()
        }
    }

    // ------------------------------------------------------------------ //
    //  MENU (ícone de configurações na toolbar)                           //
    // ------------------------------------------------------------------ //

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Configurações")
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            startActivity(android.content.Intent(this, ConfigActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

        // ------------------------------------------------------------------ //
    //  UI                                                                  //
    // ------------------------------------------------------------------ //

    private fun updateUI() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { database.getLocationCount() }

            val isRunning = GpsTrackingService.isRunning
            val lastTime  = GpsTrackingService.lastLocationTime
            val resets    = GpsTrackingService.watchdogResetCount

            // Status geral
            statusText.text = if (isRunning) "● Rastreando" else "○ Parado"
            statusText.setTextColor(
                if (isRunning)
                    getColor(android.R.color.holo_green_dark)
                else
                    getColor(android.R.color.darker_gray)
            )

            // Contagem
            countText.text = "Localizações registradas: $count"

            // Último ponto registrado
            if (lastTime > 0L) {
                val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                val tempoDecorrido = tempoDecorrido(lastTime)
                lastLocationText.text = "Último ponto: ${fmt.format(Date(lastTime))} ($tempoDecorrido)"

                // Alerta visual se faz muito tempo sem registro
                val silenceMin = (System.currentTimeMillis() - lastTime) / 60_000
                lastLocationText.setTextColor(
                    when {
                        silenceMin > 15 -> getColor(android.R.color.holo_red_dark)
                        silenceMin > 5  -> getColor(android.R.color.holo_orange_dark)
                        else            -> getColor(android.R.color.holo_green_dark)
                    }
                )
            } else {
                lastLocationText.text = "Último ponto: aguardando primeiro sinal..."
                lastLocationText.setTextColor(getColor(android.R.color.darker_gray))
            }

            // Info do watchdog
            watchdogText.text = if (resets > 0)
                "⚠ Watchdog reiniciou o GPS $resets vez(es)"
            else
                "Watchdog: OK"

            startButton.isEnabled = !isRunning
            stopButton.isEnabled  = isRunning
        }
    }

    private fun tempoDecorrido(timestamp: Long): String {
        val diffMs  = System.currentTimeMillis() - timestamp
        val diffMin = diffMs / 60_000
        val diffH   = diffMin / 60
        return when {
            diffMin < 1  -> "agora"
            diffMin < 60 -> "há ${diffMin}min"
            else         -> "há ${diffH}h ${diffMin % 60}min"
        }
    }
}
