package com.gpstracker.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class GpsDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "gps_tracker.db"
        private const val DATABASE_VERSION = 4 // ↑ bump versão

        private const val TABLE_LOCATIONS = "locations"
        private const val COL_ID = "id"
        private const val COL_LATITUDE = "latitude"
        private const val COL_LONGITUDE = "longitude"
        private const val COL_ALTITUDE = "altitude"
        private const val COL_ACCURACY = "accuracy"
        private const val COL_SPEED = "speed"
        private const val COL_BEARING = "bearing"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_BATTERY = "battery"
        private const val COL_SYNCED = "synced"

        private const val TABLE_ATTEMPTS = "gps_attempts"
        private const val COL_ATT_ID = "id"
        private const val COL_ATT_TIME = "timestamp"
        private const val COL_ATT_SATS = "satellites"
        private const val COL_ATT_ACC = "accuracy"
        private const val COL_ATT_STATUS = "status"
        private const val COL_ATT_REASON = "reason"

        @Volatile
        private var INSTANCE: GpsDatabase? = null

        fun getInstance(context: Context): GpsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = GpsDatabase(context)
                INSTANCE = instance
                instance
            }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {

        db.execSQL("""
            CREATE TABLE $TABLE_LOCATIONS (
                $COL_ID        INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_LATITUDE  REAL NOT NULL,
                $COL_LONGITUDE REAL NOT NULL,
                $COL_ALTITUDE  REAL NOT NULL,
                $COL_ACCURACY  REAL NOT NULL,
                $COL_SPEED     REAL NOT NULL,
                $COL_BEARING   REAL NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_BATTERY   INTEGER DEFAULT -1,
                $COL_SYNCED    INTEGER DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_LOCATIONS($COL_TIMESTAMP)")
        db.execSQL("CREATE INDEX idx_synced ON $TABLE_LOCATIONS($COL_SYNCED)")

        db.execSQL("""
            CREATE TABLE $TABLE_ATTEMPTS (
                $COL_ATT_ID     INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ATT_TIME   INTEGER NOT NULL,
                $COL_ATT_SATS   INTEGER DEFAULT 0,
                $COL_ATT_ACC    REAL DEFAULT -1,
                $COL_ATT_STATUS TEXT NOT NULL,
                $COL_ATT_REASON TEXT DEFAULT ''
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_att_time ON $TABLE_ATTEMPTS($COL_ATT_TIME)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {

        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_LOCATIONS ADD COLUMN $COL_BATTERY INTEGER DEFAULT -1")
        }

        if (oldVersion < 3) {
            db.execSQL("""
                CREATE TABLE $TABLE_ATTEMPTS (
                    $COL_ATT_ID     INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_ATT_TIME   INTEGER NOT NULL,
                    $COL_ATT_SATS   INTEGER DEFAULT 0,
                    $COL_ATT_ACC    REAL DEFAULT -1,
                    $COL_ATT_STATUS TEXT NOT NULL,
                    $COL_ATT_REASON TEXT DEFAULT ''
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX idx_att_time ON $TABLE_ATTEMPTS($COL_ATT_TIME)")
        }

        if (oldVersion < 4) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_synced ON $TABLE_LOCATIONS($COL_SYNCED)")
        }
    }

    // ---------------------------------------------------------
    // INSERT LOCATION (OTIMIZADO)
    // ---------------------------------------------------------

    fun insertLocation(location: GpsLocation): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_LATITUDE, location.latitude)
            put(COL_LONGITUDE, location.longitude)
            put(COL_ALTITUDE, location.altitude)
            put(COL_ACCURACY, location.accuracy)
            put(COL_SPEED, location.speed)
            put(COL_BEARING, location.bearing)
            put(COL_TIMESTAMP, location.timestamp)
            put(COL_BATTERY, location.battery)
            put(COL_SYNCED, if (location.synced) 1 else 0)
        }
        return db.insert(TABLE_LOCATIONS, null, values)
    }

    fun getAllLocations(): List<GpsLocation> {
        val list = mutableListOf<GpsLocation>()
        readableDatabase.query(
            TABLE_LOCATIONS,
            null,
            null,
            null,
            null,
            null,
            "$COL_TIMESTAMP ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(GpsLocation(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                    latitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_LATITUDE)),
                    longitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_LONGITUDE)),
                    altitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_ALTITUDE)),
                    accuracy = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_ACCURACY)),
                    speed = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_SPEED)),
                    bearing = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_BEARING)),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
                    battery = cursor.getInt(cursor.getColumnIndexOrThrow(COL_BATTERY)),
                    synced = cursor.getInt(cursor.getColumnIndexOrThrow(COL_SYNCED)) == 1
                ))
            }
        }
        return list
    }

    fun getLocationCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_LOCATIONS",
            null
        ).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun clearLocations() {
        writableDatabase.delete(TABLE_LOCATIONS, null, null)
    }

    fun markAsSynced(ids: List<Long>) {
        if (ids.isEmpty()) return

        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put(COL_SYNCED, 1) }
            val args = ids.joinToString(",")
            db.update(TABLE_LOCATIONS, values, "$COL_ID IN ($args)", null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------------------------------------------------------
    // ATTEMPTS
    // ---------------------------------------------------------

    fun insertAttempt(attempt: GpsAttempt): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ATT_TIME, attempt.timestamp)
            put(COL_ATT_SATS, attempt.satellites)
            put(COL_ATT_ACC, attempt.accuracy)
            put(COL_ATT_STATUS, attempt.status)
            put(COL_ATT_REASON, attempt.reason)
        }
        return db.insert(TABLE_ATTEMPTS, null, values)
    }

    fun getRecentAttempts(limit: Int = 50): List<GpsAttempt> {
        val list = mutableListOf<GpsAttempt>()
        readableDatabase.query(
            TABLE_ATTEMPTS,
            null,
            null,
            null,
            null,
            null,
            "$COL_ATT_TIME DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(GpsAttempt(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ATT_ID)),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ATT_TIME)),
                    satellites = cursor.getInt(cursor.getColumnIndexOrThrow(COL_ATT_SATS)),
                    accuracy = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_ATT_ACC)),
                    status = cursor.getString(cursor.getColumnIndexOrThrow(COL_ATT_STATUS)),
                    reason = cursor.getString(cursor.getColumnIndexOrThrow(COL_ATT_REASON)) ?: ""
                ))
            }
        }
        return list
    }

    fun clearAttempts() {
        writableDatabase.delete(TABLE_ATTEMPTS, null, null)
    }
}
