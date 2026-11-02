package com.remotecontrolplus.data

import android.content.Context

/** Per-device pairing credentials, encrypted at rest with [SecureStore]. */
class CredentialStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    fun get(deviceId: String): String? = prefs.getString(deviceId, null)?.let(SecureStore::decrypt)

    fun put(deviceId: String, credential: String) {
        prefs.edit().putString(deviceId, SecureStore.encrypt(credential)).apply()
    }

    fun remove(deviceId: String) {
        prefs.edit().remove(deviceId).apply()
    }
}
