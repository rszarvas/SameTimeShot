package com.sametime.shot.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * WiFi hálózat (hotspot) kezelése a vezérlőn.
 * Android 12+ szükséges a SoftAP mód használatához.
 */
@RequiresApi(Build.VERSION_CODES.S) // Android 12+
class WifiHotspotManager(private val context: Context) {

    companion object {
        private const val TAG = "STS-WiFiHotspot"
        private const val NETWORK_NAME = "sametimeshot"
        private const val NETWORK_PASSWORD = "sametimeshot123" // 8+ karakter szükséges
    }

    private val wifiManager: WifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var hotspotActive = false

    /**
     * WiFi hálózat indítása (SoftAP mód)
     * Ez létrehozza a "sametimeshot" hálózatot a 192.168.43.x tartományon
     */
    @SuppressLint("MissingPermission")
    fun startHotspot(onResult: (success: Boolean, message: String) -> Unit) {
        if (hotspotActive) {
            onResult(false, "Hotspot már aktív")
            return
        }

        try {
            Log.d(TAG, "Hotspot indítása: $NETWORK_NAME")

            // SoftAP konfigurálása (Android 12+)
            @Suppress("UNCHECKED_CAST")
            val configClass = Class.forName("android.net.wifi.WifiManager\$SoftApConfiguration")
            val builderClass = Class.forName("android.net.wifi.WifiManager\$SoftApConfiguration\$Builder")

            val builder = builderClass.getDeclaredConstructor().newInstance()
            val setSsidMethod = builderClass.getMethod("setSsid", String::class.java)
            val setPassphraseMethod = builderClass.getMethod("setPassphrase", String::class.java, Int::class.java)
            val setMaxClientsMethod = builderClass.getMethod("setMaxNumberOfClients", Int::class.java)
            val buildMethod = builderClass.getMethod("build")

            setSsidMethod.invoke(builder, NETWORK_NAME)
            setPassphraseMethod.invoke(builder, NETWORK_PASSWORD, 2) // WPA2 = 2
            setMaxClientsMethod.invoke(builder, 30)

            val config = buildMethod.invoke(builder)

            // Hotspot indítása
            val startSoftApMethod = WifiManager::class.java.getMethod("startSoftAp", configClass)
            val success = startSoftApMethod.invoke(wifiManager, config) as Boolean

            if (success) {
                hotspotActive = true
                Log.d(TAG, "✓ Hotspot sikeresen indítva")
                onResult(true, "WiFi hálózat aktív: $NETWORK_NAME")
            } else {
                Log.e(TAG, "✗ Hotspot indítás sikertelen")
                onResult(false, "WiFi hálózat indítása sikertelen")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Hiba a hotspot indítása közben", e)
            onResult(false, "Hiba: ${e.message}")
        }
    }

    /**
     * WiFi hálózat leállítása
     */
    @SuppressLint("MissingPermission")
    fun stopHotspot(onResult: (success: Boolean, message: String) -> Unit) {
        if (!hotspotActive) {
            onResult(false, "Hotspot nincs aktív")
            return
        }

        try {
            Log.d(TAG, "Hotspot leállítása")
            val stopSoftApMethod = WifiManager::class.java.getMethod("stopSoftAp")
            val success = stopSoftApMethod.invoke(wifiManager) as Boolean

            if (success) {
                hotspotActive = false
                Log.d(TAG, "✓ Hotspot sikeresen leállítva")
                onResult(true, "WiFi hálózat leállítva")
            } else {
                Log.e(TAG, "✗ Hotspot leállítás sikertelen")
                onResult(false, "WiFi hálózat leállítása sikertelen")
            }

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
     */
    @SuppressLint("MissingPermission")
    fun getConnectedClientsCount(): Int {
        return try {
            val method = WifiManager::class.java.getMethod("getSoftApConnectedClients")
            @Suppress("UNCHECKED_CAST")
            val clients = method.invoke(wifiManager) as? List<*>
            clients?.size ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Hiba a kliensek számlálása közben", e)
            0
        }
    }
}
