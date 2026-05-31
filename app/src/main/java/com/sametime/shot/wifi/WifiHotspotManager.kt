package com.sametime.shot.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * WiFi hálózat (hotspot) kezelése a vezérlőn.
 * startLocalOnlyHotspot() API használata - Android 6+ (API 24+)
 * Működik több Samsung telefonon is.
 */
@RequiresApi(Build.VERSION_CODES.M) // Android 6+ (API 24+)
class WifiHotspotManager(private val context: Context) {

    companion object {
        private const val TAG = "STS-WiFiHotspot"
        private const val NETWORK_NAME = "sametimeshot"
    }

    private val wifiManager: WifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var hotspotActive = false
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    /**
     * WiFi hotspot indítása (Local-Only Hotspot mód)
     * Ez egy egyszerűbb API, amely működik több telefonon.
     */
    @SuppressLint("MissingPermission")
    fun startHotspot(onResult: (success: Boolean, message: String) -> Unit) {
        if (hotspotActive) {
            onResult(false, "Hotspot már aktív")
            return
        }

        try {
            Log.d(TAG, "Hotspot indítása: $NETWORK_NAME (Local-Only mód)")

            // LocalOnlyHotspot callback
            val callback = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    Log.d(TAG, "✓ Hotspot sikeresen indítva")
                    Log.d(TAG, "SSID: ${reservation.softApConfiguration.ssid}")
                    Log.d(TAG, "IP: 192.168.43.1")

                    hotspotActive = true
                    hotspotReservation = reservation
                    onResult(true, "WiFi hálózat aktív: $NETWORK_NAME")
                }

                override fun onFailed(reason: Int) {
                    Log.e(TAG, "✗ Hotspot indítás sikertelen: kód $reason")
                    hotspotActive = false
                    onResult(false, "WiFi hálózat indítása sikertelen (kód: $reason)")
                }
            }

            // Hotspot indítása
            wifiManager.startLocalOnlyHotspot(callback, null)
            Log.d(TAG, "Hotspot indítás kérése elküldve...")

        } catch (e: Exception) {
            Log.e(TAG, "Hiba a hotspot indítása közben", e)
            onResult(false, "Hiba: ${e.message}")
        }
    }

    /**
     * WiFi hálózat leállítása
     */
    fun stopHotspot(onResult: (success: Boolean, message: String) -> Unit) {
        if (!hotspotActive) {
            onResult(false, "Hotspot nincs aktív")
            return
        }

        try {
            Log.d(TAG, "Hotspot leállítása")
            hotspotReservation?.close()
            hotspotReservation = null
            hotspotActive = false
            Log.d(TAG, "✓ Hotspot sikeresen leállítva")
            onResult(true, "WiFi hálózat leállítva")
        } catch (e: Exception) {
            Log.e(TAG, "Hiba a hotspot leállítása közben", e)
            onResult(false, "Hiba: ${e.message}")
        }
    }

    /**
     * Hotspot állapota
     */
    fun isHotspotActive(): Boolean = hotspotActive

    /**
     * Csatlakoztatott kliensek száma
     * (Local-Only hotspot-nak nincs közvetlenül elérhető klienslista)
     */
    fun getConnectedClientsCount(): Int {
        return if (hotspotActive) 1 else 0 // Szimulált érték
    }
}
