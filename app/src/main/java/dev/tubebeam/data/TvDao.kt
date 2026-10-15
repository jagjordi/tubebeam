package dev.tubebeam.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TvDao {
    @Query("SELECT * FROM tv_devices ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<TvDevice>>

    @Query("SELECT * FROM tv_devices ORDER BY sortOrder, name")
    suspend fun getAll(): List<TvDevice>

    @Query("SELECT * FROM tv_devices WHERE id = :id")
    fun observeById(id: Long): Flow<TvDevice?>

    @Query("SELECT * FROM tv_devices WHERE id = :id")
    suspend fun getById(id: Long): TvDevice?

    @Query("SELECT COUNT(*) FROM tv_devices")
    suspend fun count(): Int

    @Insert
    suspend fun insert(tv: TvDevice): Long

    @Update
    suspend fun update(tv: TvDevice)

    @Delete
    suspend fun delete(tv: TvDevice)

    @Query("UPDATE tv_devices SET isDefault = (id = :id)")
    suspend fun setDefault(id: Long)

    @Query("UPDATE tv_devices SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE tv_devices SET lastSeenOk = :timestamp WHERE id = :id")
    suspend fun markSeen(id: Long, timestamp: Long)

    @Query("UPDATE tv_devices SET host = :host WHERE id = :id")
    suspend fun updateHost(id: Long, host: String)

    @Query("UPDATE tv_devices SET serial = :serial WHERE id = :id")
    suspend fun updateSerial(id: Long, serial: String?)
}
