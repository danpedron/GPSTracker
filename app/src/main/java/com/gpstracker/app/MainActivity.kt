package com.gpstracker.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var lastLocationText: TextView
    private lateinit var watchdogText: TextView
    private lateinit var countText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var syncButton: Button
    private lateinit var clearButton: Button
    private lateinit var autoStartCheckbox: CheckBox

    private lateinit var database: GpsDatabase

    private var updateJob: Job? = null
    private var updatePending = false

    private val dateFormatter = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
    }

    // ------------------------------------------------------------ //
    // RECEIVER
    // ------------------------------------------------------------ //

    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            requestUIUpdate()
        }
    }

    // Atualização periódica
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            requestUIUpdate()
            refreshHandler.postDelayed(this, 30_000L)
        }
    }

    // ------------------------------------------------------------ //
    // CICLO DE VIDA
    // ------------------------------------------------------------ //

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        database = GpsDatabase.getInstance(this)

        statusText = findViewById(R.id.statusText)
        lastLocationText = findViewById(R.id.lastLocationText)
        watchdogText = findViewById(R.id.watchdogText)
        countText = findViewById(R.id.countText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        syncButton = findViewById(R.id.syncButton)
        clearButton = findViewById(R.id.clearButton)
        autoStartCheckbox = findViewById(R.id.autoStartCheckbox)

        autoStartCheckbox.isChecked = BootReceiver.PrefsHelper.isAutoStartEnabled(this)
        autoStartCheckbox.setOnCheckedChangeListener { _, isChecked ->
            BootReceiver.PrefsHelper.setAutoStartEnabled(this, isChecked)
        }

        startButton.setOnClickListener { startTracking() }
        stopButton.setOnClickListener { stopTracking() }
        syncButton.setOnClickListener { syncData() }
        clearButton.setOnClickListener { clearData() }

        checkPermissions()
        requestUIUpdate()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(uiUpdateReceiver, IntentFilter(GpsTrackingService.ACTION_UPDATE_UI))
        refreshHandler.post(refreshRunnable)
        requestUIUpdate()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(uiUpdateReceiver)
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    // ------------------------------------------------------------ //
    // CONTROLES
    // ------------------------------------------------------------ //

    private fun startTracking() {
        val serviceIntent = Intent(this, GpsTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(serviceIntent)
        else
            startService(serviceIntent)

        BootReceiver.PrefsHelper.setWasTracking(this, true)
        requestUIUpdate()
    }

    private fun stopTracking() {
        stopService(Intent(this, GpsTrackingService::class.java))
        BootReceiver.PrefsHelper.setWasTracking(this, false)
        requestUIUpdate()
    }

    private fun syncData() {
        lifecycleScope.launch {
            syncButton.isEnabled = false
            try {
                val locations = withContext(Dispatchers.IO) { database.getAllLocations() }

                if (locations.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Nenhum dado para sincronizar", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val success = withContext(Dispatchers.IO) {
                    ApiClient.syncLocations(this@MainActivity, locations)
                }

                if (success) {
                    withContext(Dispatchers.IO) { database.clearLocations() }
                    GpsTrackingService.lastLocationCount = 0
                    Toast.makeText(this@MainActivity, "Sincronização concluída com sucesso", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Falha ao sincronizar dados", Toast.LENGTH_SHORT).show()
                }

                requestUIUpdate()
            } finally {
                syncButton.isEnabled = true
            }
        }
    }

    private fun clearData() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { database.clearLocations() }
            GpsTrackingService.lastLocationCount = 0
            requestUIUpdate()
        }
    }

    // ------------------------------------------------------------ //
    // UI OTIMIZADA
    // ------------------------------------------------------------ //

    private fun requestUIUpdate() {
        if (updatePending) return
        updatePending = true

        refreshHandler.postDelayed({
            updatePending = false
            updateUI()
        }, 300) // debounce 300ms
    }

    private fun updateUI() {
        updateJob?.cancel()

        updateJob = lifecycleScope.launch {
            val count = GpsTrackingService.lastLocationCount
                .takeIf { it >= 0 }
                ?: withContext(Dispatchers.IO) { database.getLocationCount() }

            val isRunning = GpsTrackingService.isRunning
            val lastTime = GpsTrackingService.lastLocationTime
            val resets = GpsTrackingService.watchdogResetCount

            statusText.text = if (isRunning) "● Rastreando" else "○ Parado"
            countText.text = "Localizações registradas: $count"

            if (lastTime > 0L) {
                val silenceMin = (System.currentTimeMillis() - lastTime) / 60_000
                val tempo = tempoDecorrido(lastTime)

                lastLocationText.text =
                    "Último ponto: ${dateFormatter.format(Date(lastTime))} ($tempo)"

                lastLocationText.setTextColor(
                    when {
                        silenceMin > 15 -> getColor(android.R.color.holo_red_dark)
                        silenceMin > 5 -> getColor(android.R.color.holo_orange_dark)
                        else -> getColor(android.R.color.holo_green_dark)
                    }
                )
            } else {
                lastLocationText.text = "Último ponto: aguardando primeiro sinal..."
                lastLocationText.setTextColor(getColor(android.R.color.darker_gray))
            }

            watchdogText.text =
                if (resets > 0)
                    "⚠ Watchdog reiniciou o GPS $resets vez(es)"
                else
                    "Watchdog: OK"

            startButton.isEnabled = !isRunning
            stopButton.isEnabled = isRunning
        }
    }

    private fun tempoDecorrido(timestamp: Long): String {
        val diffMin = (System.currentTimeMillis() - timestamp) / 60_000
        val diffH = diffMin / 60
        return when {
            diffMin < 1 -> "agora"
            diffMin < 60 -> "há ${diffMin}min"
            else -> "há ${diffH}h ${diffMin % 60}min"
        }
    }

    // ------------------------------------------------------------ //
    // MENU
    // ------------------------------------------------------------ //

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Configurações")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            startActivity(Intent(this, ConfigActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------ //
    // PERMISSÕES
    // ------------------------------------------------------------ //

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty())
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), LOCATION_PERMISSION_REQUEST)
    }
}
