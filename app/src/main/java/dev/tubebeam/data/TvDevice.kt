package dev.tubebeam.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tv_devices")
data class TvDevice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                                  // "Living Room"
    val host: String,                                  // "192.168.1.50"
    val port: Int = 5555,                              // legacy; for TLS this is the last known connect port
    val mode: ConnectionMode = ConnectionMode.LEGACY_TCP,
    val targetPackage: String? = null,                 // detected SmartTube package; null = auto-detect at send time
    val isDefault: Boolean = false,
    val lastSeenOk: Long? = null,                      // epoch ms of last successful send
    val sortOrder: Int = 0,
)
