package com.ped.mobile.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class SyncSettings(context: Context) {
    private val prefs = context.getSharedPreferences("cims-auto-sync", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).commit() }
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", false)
        set(value) { prefs.edit().putBoolean("wifi_only", value).commit() }
    fun allowsNetwork(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!wifiOnly || (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)))
    }
}
