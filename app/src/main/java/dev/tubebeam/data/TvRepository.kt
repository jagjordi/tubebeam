package dev.tubebeam.data

import kotlinx.coroutines.flow.Flow

class TvRepository(private val dao: TvDao) {
    val tvs: Flow<List<TvDevice>> = dao.observeAll()

    fun observe(id: Long): Flow<TvDevice?> = dao.observeById(id)
    suspend fun get(id: Long): TvDevice? = dao.getById(id)
    suspend fun all(): List<TvDevice> = dao.getAll()

    /** Inserts; the first TV ever added becomes the default automatically. */
    suspend fun add(tv: TvDevice): Long {
        val first = dao.count() == 0
        return dao.insert(tv.copy(isDefault = tv.isDefault || first))
    }

    suspend fun update(tv: TvDevice) = dao.update(tv)

    suspend fun delete(tv: TvDevice) {
        dao.delete(tv)
        // Keep the invariant "if any TVs exist, at most one is default; a lone TV is the default".
        val rest = dao.getAll()
        if (rest.size == 1 && !rest[0].isDefault) dao.setDefault(rest[0].id)
    }

    /** null clears the default. */
    suspend fun setDefault(id: Long?) = if (id == null) dao.clearDefault() else dao.setDefault(id)

    suspend fun markSeen(id: Long, timestamp: Long = System.currentTimeMillis()) = dao.markSeen(id, timestamp)

    /** Called when the TV was found at a new address. */
    suspend fun updateHost(id: Long, host: String) = dao.updateHost(id, host)

    /** Records the TV's identity the first time we learn it; never overwrites a known one with null. */
    suspend fun rememberSerial(id: Long, serial: String?) {
        if (serial != null) dao.updateSerial(id, serial)
    }
}
