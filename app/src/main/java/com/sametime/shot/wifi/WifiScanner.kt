package com.sametime.shot.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * WiFi szkenner és csatlakozás kezelése a klienseken.
 * Csatlakozik a "sametimeshot" hálózathoz.
 */
@RequiresApi(Build.VERSION_CODES.S) // Android 12+
class WifiScanner(private val context: Context) {

    companion object {
        private const val TAG = "STS-WiFiScanner"
        private const val TARGET_SSID = "sametimeshot"
        private const val TARGET_PASSWORD = "sametimeshot123"
    }

    private val wifiManager: WifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var currentNetwork: Network? = null
    private var isConnected = false

    /**
     * Csatlakozás a WiFi hálózathoz (dinamikus SSID-val)
     *
     * Az Android Local-Only Hotspot nem kompatibilis a WifiNetworkSpecifier-rel.
     * Helyette: szimulált csatlakozás, majd TCP kliens közvetlenül csatlakozik a 192.168.43.1:9999-hez.
     */
    @SuppressLint("MissingPermission")
    fun connectToSameTimeShot(ssid: String = TARGET_SSID, password: String = TARGET_PASSWORD, onResult: (success: Boolean, message: String) -> Unit) {
        Log.d(TAG, "Csatlakozás a $ssid hálózathoz - szimulált mód (TCP közvetlen csatlakozás)")
        Log.d(TAG, "✓ Szimulált csatlakozás: $ssid (192.168.43.1:9999 - közvetlen TCP)")

        // Local-Only Hotspot esetén: szimulálunk, TCP kliens közvetlenül csatlakozik
        isConnected = true
        onResult(true, "Csatlakozva: $ssid (TCP direct mode)")
    }

    /**
     * Szkenner indítása (megkeresi az elérhető WiFi hálózatokat)
     */
    @SuppressLint("MissingPermission")
    fun scanNetworks(onResult: (networks: List<String>) -> Unit) {
        try {
            Log.d(TAG, "WiFi szkenner indítása")

            wifiManager.startScan()
            val results = wifiManager.scanResults
            val networkNames = results.map { it.SSID }.distinct()

            Log.d(TAG, "Talált hálózatok: $networkNames")
            onResult(networkNames)

        } catch (e: Exception) {
            Log.e(TAG, "Hiba a szkenner közben", e)
            onResult(emptyList())
        }
    }

    /**
     * Csatlakozási állapot
     */
    fun isConnected(): Boolean = isConnected

    /**
     * A korábbi WiFi-hez való visszacsatlakozás (ha szükséges)
     */
    fun disconnectAndRestorePrevious() {
        try {
            Log.d(TAG, "Lecsatlakozás a SameTimeShot hálózatról")
            connectivityManager.bindProcessToNetwork(null)
            currentNetwork?.let { connectivityManager.unregisterNetworkCallback(object : ConnectivityManager.NetworkCallback() {}) }
            isConnected = false
            Log.d(TAG, "✓ Lecsatlakozva")
        } catch (e: Exception) {
            Log.e(TAG, "Hiba a lecsatlakozás közben", e)
        }
    }
}
