package com.gpstracker.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class GpsDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    
    companion object {
        private const val DATABASE_NAME = "gps_tracker.db"
        private const val DATABASE_VERSION = 1
        
        private const val TABLE_LOCATIONS = "locations"
        private const val COLUMN_ID = "id"
        private const val COLUMN_LATITUDE = "latitude"
        private const val COLUMN_LONGITUDE = "longitude"
        private const val COLUMN_ALTITUDE = "altitude"
        private const val COLUMN_ACCURACY = "accuracy"
        private const val COLUMN_SPEED = "speed"
        private const val COLUMN_BEARING = "bearing"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_SYNCED = "synced"
    }
    
    override fun onCreate(db: SQLiteDatabase?) {
        val createTable = """
            CREATE TABLE $TABLE_LOCATIONS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_LATITUDE REAL NOT NULL,
                $COLUMN_LONGITUDE REAL NOT NULL,
                $COLUMN_ALTITUDE REAL NOT NULL,
                $COLUMN_ACCURACY REAL NOT NULL,
                $COLUMN_SPEED REAL NOT NULL,
                $COLUMN_BEARING REAL NOT NULL,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_SYNCED INTEGER DEFAULT 0
            )
        """.trimIndent()
        
        db?.execSQL(createTable)
        
        // Criar índice para melhorar performance
        db?.execSQL("CREATE INDEX idx_timestamp ON $TABLE_LOCATIONS($COLUMN_TIMESTAMP)")
    }
    
    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        db?.execSQL("DROP TABLE IF EXISTS $TABLE_LOCATIONS")
        onCreate(db)
    }
    
    fun insertLocation(location: GpsLocation): Long {
        val db = writableDatabase
        
        val values = ContentValues().apply {
            put(COLUMN_LATITUDE, location.latitude)
            put(COLUMN_LONGITUDE, location.longitude)
            put(COLUMN_ALTITUDE, location.altitude)
            put(COLUMN_ACCURACY, location.accuracy)
            put(COLUMN_SPEED, location.speed)
            put(COLUMN_BEARING, location.bearing)
            put(COLUMN_TIMESTAMP, location.timestamp)
            put(COLUMN_SYNCED, if (location.synced) 1 else 0)
        }
        
        return db.insert(TABLE_LOCATIONS, null, values)
    }
    
    fun getAllLocations(): List<GpsLocation> {
        val locations = mutableListOf<GpsLocation>()
        val db = readableDatabase
        
        val cursor = db.query(
            TABLE_LOCATIONS,
            null,
            null,
            null,
            null,
            null,
            "$COLUMN_TIMESTAMP ASC"
        )
        
        cursor.use {
            while (it.moveToNext()) {
                locations.add(
                    GpsLocation(
                        id = it.getLong(it.getColumnIndexOrThrow(COLUMN_ID)),
                        latitude = it.getDouble(it.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                        longitude = it.getDouble(it.getColumnIndexOrThrow(COLUMN_LONGITUDE)),
                        altitude = it.getDouble(it.getColumnIndexOrThrow(COLUMN_ALTITUDE)),
                        accuracy = it.getFloat(it.getColumnIndexOrThrow(COLUMN_ACCURACY)),
                        speed = it.getFloat(it.getColumnIndexOrThrow(COLUMN_SPEED)),
                        bearing = it.getFloat(it.getColumnIndexOrThrow(COLUMN_BEARING)),
                        timestamp = it.getLong(it.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                        synced = it.getInt(it.getColumnIndexOrThrow(COLUMN_SYNCED)) == 1
                    )
                )
            }
        }
        
        return locations
    }
    
    fun getLocationCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_LOCATIONS", null)
        
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        
        return 0
    }
    
    fun clearLocations() {
        val db = writableDatabase
        db.delete(TABLE_LOCATIONS, null, null)
    }
    
    fun markAsSynced(ids: List<Long>) {
        val db = writableDatabase
        
        val values = ContentValues().apply {
            put(COLUMN_SYNCED, 1)
        }
        
        val placeholders = ids.joinToString(",") { "?" }
        val whereClause = "$COLUMN_ID IN ($placeholders)"
        val whereArgs = ids.map { it.toString() }.toTypedArray()
        
        db.update(TABLE_LOCATIONS, values, whereClause, whereArgs)
    }
}
