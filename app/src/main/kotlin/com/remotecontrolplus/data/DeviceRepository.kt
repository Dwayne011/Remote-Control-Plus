package com.remotecontrolplus.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.remotecontrolplus.core.TvDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "devices")

/** The saved TVs and which one is currently selected. */
class DeviceRepository(
    context: Context,
    private val credentials: CredentialStore,
) {
    private val store = context.applicationContext.dataStore
    private val json = Json { ignoreUnknownKeys = true }
    private val devicesKey = stringPreferencesKey("devices")
    private val selectedKey = stringPreferencesKey("selected")
    private val padModeKey = stringPreferencesKey("padMode")

    val devices: Flow<List<TvDevice>> = store.data.map { prefs -> decode(prefs[devicesKey]) }
    val selectedId: Flow<String?> = store.data.map { it[selectedKey] }

    /** "swipe" or "dpad"; how the touch pad behaves. */
    val padMode: Flow<String> = store.data.map { it[padModeKey] ?: "swipe" }

    suspend fun setPadMode(mode: String) {
        store.edit { it[padModeKey] = mode }
    }

    suspend fun save(device: TvDevice) {
        store.edit { prefs ->
            val current = decode(prefs[devicesKey]).filterNot { it.id == device.id }
            prefs[devicesKey] = encode(current + device)
            if (prefs[selectedKey] == null) prefs[selectedKey] = device.id
        }
    }

    suspend fun forget(deviceId: String) {
        credentials.remove(deviceId)
        store.edit { prefs ->
            val remaining = decode(prefs[devicesKey]).filterNot { it.id == deviceId }
            prefs[devicesKey] = encode(remaining)
            if (prefs[selectedKey] == deviceId) {
                val next = remaining.firstOrNull()?.id
                if (next == null) prefs.remove(selectedKey) else prefs[selectedKey] = next
            }
        }
    }

    suspend fun select(deviceId: String) {
        store.edit { it[selectedKey] = deviceId }
    }

    suspend fun get(deviceId: String): TvDevice? = devices.first().firstOrNull { it.id == deviceId }

    private fun decode(raw: String?): List<TvDevice> =
        raw?.let { runCatching { json.decodeFromString(ListSerializer(TvDevice.serializer()), it) }.getOrNull() }
            ?: emptyList()

    private fun encode(list: List<TvDevice>): String =
        json.encodeToString(ListSerializer(TvDevice.serializer()), list)
}
