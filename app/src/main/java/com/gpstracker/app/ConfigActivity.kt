package com.gpstracker.app

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class ConfigActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var deviceIdEdit: EditText
    private lateinit var profileGroup: RadioGroup
    private lateinit var profileDescText: TextView
    private lateinit var batteryChartText: TextView
    private lateinit var saveButton: Button

    // WiFi auto-sync
    private lateinit var wifiAutoSyncCheckbox: CheckBox
    private lateinit var wifiNetwork1Edit: EditText
    private lateinit var wifiNetwork2Edit: EditText
    private lateinit var wifiNetwork3Edit: EditText
    private lateinit var wifiDetectButton: Button

    companion object {
        const val PREFS_NAME   = "gps_tracker_prefs"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PROFILE   = "battery_profile"

        // Perfis disponíveis
        const val PROFILE_PRECISION   = 0  // Alta precisão  ~4-5h
        const val PROFILE_BALANCED    = 1  // Balanceado     ~8-10h
        const val PROFILE_ECONOMY     = 2  // Econômico      ~14-18h  (padrão)
        const val PROFILE_MAX_ECONOMY = 3  // Máx. economia  ~20-24h

        fun getProfile(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_PROFILE, PROFILE_ECONOMY)
        }

        fun getDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var id = prefs.getString(KEY_DEVICE_ID, "") ?: ""
            if (id.isEmpty()) {
                // Gera um ID padrão baseado no modelo do aparelho
                id = android.os.Build.MODEL.replace(" ", "_")
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

        // Retorna os parâmetros de localização para o perfil escolhido
        data class LocationParams(
            val intervalMs: Long,
            val minIntervalMs: Long,
            val minDistanceM: Float,
            val maxDelayMs: Long,
            val priority: Int
        )

        // Redes WiFi confiáveis para sincronização automática
        private const val KEY_TRUSTED_NETWORKS = "trusted_networks"
        private const val NETWORKS_SEPARATOR    = "||"

        fun getTrustedNetworks(context: Context): List<String> {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TRUSTED_NETWORKS, "") ?: ""
            return if (raw.isEmpty()) emptyList()
            else raw.split(NETWORKS_SEPARATOR).filter { it.isNotBlank() }
        }

        fun saveTrustedNetworks(context: Context, networks: List<String>) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TRUSTED_NETWORKS, networks.joinToString(NETWORKS_SEPARATOR))
                .apply()
        }

        fun getCurrentSsid(context: Context): String? {
            return try {
                val wm = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                wm.connectionInfo?.ssid
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
            } catch (e: Exception) { null }
        }

        fun getLocationParams(profile: Int): LocationParams = when (profile) {
            PROFILE_PRECISION -> LocationParams(
                intervalMs    =  5_000L,   // 5 segundos
                minIntervalMs =  3_000L,   // 3 segundos mínimo
                minDistanceM  =  0f,       // registra sempre (sem limite de distância)
                maxDelayMs    =  5_000L,   // sem batching
                priority      = com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY
            )
            PROFILE_BALANCED -> LocationParams(
                intervalMs    =  30_000L,  // 30 segundos
                minIntervalMs =  15_000L,  // 15 segundos mínimo
                minDistanceM  =  10f,      // 10 metros
                maxDelayMs    =  60_000L,
                priority      = com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY
            )
            PROFILE_MAX_ECONOMY -> LocationParams(
                intervalMs    = 300_000L,
                minIntervalMs = 180_000L,
                minDistanceM  =  100f,
                maxDelayMs    = 600_000L,
                priority      = com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY
            )
            else -> LocationParams( // PROFILE_ECONOMY (padrão)
                intervalMs    = 120_000L,
                minIntervalMs =  60_000L,
                minDistanceM  =  50f,
                maxDelayMs    = 180_000L,
                priority      = com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        supportActionBar?.title = "Configurações"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        deviceIdEdit    = findViewById(R.id.deviceIdEdit)
        profileGroup    = findViewById(R.id.profileGroup)
        profileDescText = findViewById(R.id.profileDescText)
        batteryChartText = findViewById(R.id.batteryChartText)
        saveButton       = findViewById(R.id.saveButton)

        // WiFi
        wifiAutoSyncCheckbox = findViewById(R.id.wifiAutoSyncCheckbox)
        wifiNetwork1Edit     = findViewById(R.id.wifiNetwork1Edit)
        wifiNetwork2Edit     = findViewById(R.id.wifiNetwork2Edit)
        wifiNetwork3Edit     = findViewById(R.id.wifiNetwork3Edit)
        wifiDetectButton     = findViewById(R.id.wifiDetectButton)

        // Carregar redes salvas
        val saved = getTrustedNetworks(this)
        if (saved.isNotEmpty()) wifiNetwork1Edit.setText(saved.getOrElse(0) { "" })
        if (saved.size > 1)     wifiNetwork2Edit.setText(saved.getOrElse(1) { "" })
        if (saved.size > 2)     wifiNetwork3Edit.setText(saved.getOrElse(2) { "" })
        wifiAutoSyncCheckbox.isChecked = saved.isNotEmpty()

        // Botão "Detectar WiFi atual"
        wifiDetectButton.setOnClickListener {
            val ssid = getCurrentSsid(this)
            if (ssid != null) {
                // Preenche o primeiro campo vazio disponível
                when {
                    wifiNetwork1Edit.text.isBlank() -> wifiNetwork1Edit.setText(ssid)
                    wifiNetwork2Edit.text.isBlank() -> wifiNetwork2Edit.setText(ssid)
                    wifiNetwork3Edit.text.isBlank() -> wifiNetwork3Edit.setText(ssid)
                    else -> Toast.makeText(this, "Todos os campos estão preenchidos.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Nenhuma rede WiFi conectada no momento.", Toast.LENGTH_SHORT).show()
            }
        }

        // Carregar valores salvos
        deviceIdEdit.setText(getDeviceId(this))
        profileGroup.check(radioIdForProfile(prefs.getInt(KEY_PROFILE, PROFILE_ECONOMY)))

        // Atualizar descrição quando o usuário troca o perfil
        profileGroup.setOnCheckedChangeListener { _, checkedId ->
            updateDescription(profileForRadioId(checkedId))
        }

        updateDescription(prefs.getInt(KEY_PROFILE, PROFILE_ECONOMY))

        saveButton.setOnClickListener { save() }
    }

    private fun save() {
        val deviceId = deviceIdEdit.text.toString().trim()
        if (deviceId.isEmpty()) {
            deviceIdEdit.error = "Informe um identificador para este aparelho"
            return
        }

        val profile = profileForRadioId(profileGroup.checkedRadioButtonId)

        prefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putInt(KEY_PROFILE, profile)
            .apply()

        // Salvar redes WiFi
        if (wifiAutoSyncCheckbox.isChecked) {
            val networks = listOf(
                wifiNetwork1Edit.text.toString().trim(),
                wifiNetwork2Edit.text.toString().trim(),
                wifiNetwork3Edit.text.toString().trim()
            ).filter { it.isNotEmpty() }
            saveTrustedNetworks(this, networks)
        } else {
            saveTrustedNetworks(this, emptyList())
        }

        // Se o serviço estiver rodando, reinicia o GPS com os novos parâmetros
        if (GpsTrackingService.isRunning) {
            stopService(android.content.Intent(this, GpsTrackingService::class.java))
            val serviceIntent = android.content.Intent(this, GpsTrackingService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                startForegroundService(serviceIntent)
            else
                startService(serviceIntent)
            Toast.makeText(this, "Configurações salvas! GPS reiniciado com novo perfil.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Configurações salvas!", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun updateDescription(profile: Int) {
        val (desc, chart) = descriptionForProfile(profile)
        profileDescText.text  = desc
        batteryChartText.text = chart
    }

    private fun radioIdForProfile(profile: Int) = when (profile) {
        PROFILE_PRECISION   -> R.id.radioPrecision
        PROFILE_BALANCED    -> R.id.radioBalanced
        PROFILE_MAX_ECONOMY -> R.id.radioMaxEconomy
        else                -> R.id.radioEconomy
    }

    private fun profileForRadioId(id: Int) = when (id) {
        R.id.radioPrecision   -> PROFILE_PRECISION
        R.id.radioBalanced    -> PROFILE_BALANCED
        R.id.radioMaxEconomy  -> PROFILE_MAX_ECONOMY
        else                  -> PROFILE_ECONOMY
    }

    private fun descriptionForProfile(profile: Int): Pair<String, String> = when (profile) {

        PROFILE_PRECISION -> Pair(
            "🎯 Alta Precisão\n" +
            "Intervalo: 10s | Distância: 5m\n" +
            "Pontos/hora: ~360 | Bateria: ~20%/h\n" +
            "Duração estimada: 4-5 horas",

            "CRONOGRAMA DE BATERIA\n" +
            "─────────────────────────────\n" +
            "07:00 → 100% ████████████████████\n" +
            "08:00 →  80% ████████████████░░░░\n" +
            "09:00 →  60% ████████████░░░░░░░░\n" +
            "10:00 →  40% ████████░░░░░░░░░░░░\n" +
            "11:00 →  20% ████░░░░░░░░░░░░░░░░\n" +
            "11:30 →   0% ░░░░░░░░░░░░░░░░░░░░\n" +
            "─────────────────────────────\n" +
            "⚠ Recomendado apenas para uso curto"
        )

        PROFILE_BALANCED -> Pair(
            "⚖️ Balanceado\n" +
            "Intervalo: 60s | Distância: 20m\n" +
            "Pontos/hora: ~60 | Bateria: ~10%/h\n" +
            "Duração estimada: 8-10 horas",

            "CRONOGRAMA DE BATERIA\n" +
            "─────────────────────────────\n" +
            "07:00 → 100% ████████████████████\n" +
            "09:00 →  80% ████████████████░░░░\n" +
            "11:00 →  60% ████████████░░░░░░░░\n" +
            "13:00 →  40% ████████░░░░░░░░░░░░\n" +
            "15:00 →  20% ████░░░░░░░░░░░░░░░░\n" +
            "17:00 →   0% ░░░░░░░░░░░░░░░░░░░░\n" +
            "─────────────────────────────\n" +
            "✓ Bom para uso de meio período"
        )

        PROFILE_MAX_ECONOMY -> Pair(
            "🔋 Máxima Economia\n" +
            "Intervalo: 5min | Distância: 100m\n" +
            "Pontos/hora: ~12 | Bateria: ~4%/h\n" +
            "Duração estimada: 22-24 horas",

            "CRONOGRAMA DE BATERIA\n" +
            "─────────────────────────────\n" +
            "07:00 → 100% ████████████████████\n" +
            "10:00 →  88% ██████████████████░░\n" +
            "13:00 →  76% ███████████████░░░░░\n" +
            "16:00 →  64% ████████████░░░░░░░░\n" +
            "19:00 →  52% ██████████░░░░░░░░░░\n" +
            "22:00 →  40% ████████░░░░░░░░░░░░\n" +
            "─────────────────────────────\n" +
            "✓ Rastreia 2 dias com uma carga"
        )

        else -> Pair( // PROFILE_ECONOMY
            "🌿 Econômico  ★ Recomendado\n" +
            "Intervalo: 2min | Distância: 50m\n" +
            "Pontos/hora: ~30 | Bateria: ~6%/h\n" +
            "Duração estimada: 14-18 horas",

            "CRONOGRAMA DE BATERIA\n" +
            "─────────────────────────────\n" +
            "07:00 → 100% ████████████████████\n" +
            "09:00 →  88% ██████████████████░░\n" +
            "12:00 →  70% ██████████████░░░░░░\n" +
            "15:00 →  52% ██████████░░░░░░░░░░\n" +
            "18:00 →  34% ███████░░░░░░░░░░░░░\n" +
            "21:00 →  16% ███░░░░░░░░░░░░░░░░░\n" +
            "23:00 →  4%  ░░░░░░░░░░░░░░░░░░░░\n" +
            "─────────────────────────────\n" +
            "✓ Ideal para uso das 07h às 23h"
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
