package dev.tubebeam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Settings(
    /** Send KEYCODE_WAKEUP before the VIEW intent. */
    val wakeTv: Boolean = true,
    /** With a default TV set, share straight to it instead of showing the picker. */
    val alwaysUseDefault: Boolean = true,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            wakeTv = p[WAKE_TV] ?: true,
            alwaysUseDefault = p[ALWAYS_USE_DEFAULT] ?: true,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setWakeTv(value: Boolean) = store.edit { it[WAKE_TV] = value }
    suspend fun setAlwaysUseDefault(value: Boolean) = store.edit { it[ALWAYS_USE_DEFAULT] = value }

    private companion object {
        val WAKE_TV = booleanPreferencesKey("wake_tv")
        val ALWAYS_USE_DEFAULT = booleanPreferencesKey("always_use_default")
    }
}
