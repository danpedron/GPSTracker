package com.gpstracker.app

import android.content.Context
import android.content.SharedPreferences
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

        fun getLocationParams(profile: Int): LocationParams = when (profile) {
            PROFILE_PRECISION -> LocationParams(
                intervalMs    = 10_000L,
                minIntervalMs =  5_000L,
                minDistanceM  =  5f,
                maxDelayMs    = 15_000L,
                priority      = com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY
            )
            PROFILE_BALANCED -> LocationParams(
                intervalMs    =  60_000L,
                minIntervalMs =  30_000L,
                minDistanceM  =  20f,
                maxDelayMs    =  90_000L,
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
        saveButton      = findViewById(R.id.saveButton)

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

        Toast.makeText(this, "Configurações salvas!", Toast.LENGTH_SHORT).show()
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
