package com.sametime.shot.ui.controller

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.sametime.shot.model.ConnectedDevice
import com.sametime.shot.model.TransferState
import com.sametime.shot.model.TransferStatus
import com.sametime.shot.model.WifiClient
import com.sametime.shot.tcp.TcpServer
import com.sametime.shot.wifi.WifiHotspotManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.M) // Android 6+ (WiFi Local-Only Hotspot)
class ControllerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "STS-CtrlVM"
    }

    private val _devices = MutableLiveData<List<ConnectedDevice>>(emptyList())
    val devices: LiveData<List<ConnectedDevice>> = _devices

    // WiFi klientek külön (TCP-n keresztül)
    private val _wifiClients = MutableLiveData<List<WifiClient>>(emptyList())
    val wifiClients: LiveData<List<WifiClient>> = _wifiClients

    private val _isLocked = MutableLiveData(false)
    val isLocked: LiveData<Boolean> = _isLocked

    private val _status = MutableLiveData("Várakozás csatlakozásokra...")
    val status: LiveData<String> = _status

    // TransferState: státusz + 0-100% progress minden eszközre
    private val _transferStates = MutableLiveData<Map<String, TransferState>>(emptyMap())
    val transferStates: LiveData<Map<String, TransferState>> = _transferStates

    private val _navigateToStart = MutableLiveData(false)
    val navigateToStart: LiveData<Boolean> = _navigateToStart

    private val _hotspotName = MutableLiveData("")
    val hotspotName: LiveData<String> = _hotspotName

    private val _hotspotPassword = MutableLiveData("")
    val hotspotPassword: LiveData<String> = _hotspotPassword

    private val _hotspotActive = MutableLiveData(false)
    val hotspotActive: LiveData<Boolean> = _hotspotActive

    private var sessionTimestamp = ""
    private var photoCounter = 0
    private var serverStarted = false
    private var udpServer: DatagramSocket? = null
    private var isRunning = false

    // WiFi + TCP komponensek
    private val wifiHotspotManager = WifiHotspotManager(application)
    private lateinit var tcpServer: TcpServer

    // Bluetooth támogatás eltávolítva - csak WiFi/TCP

    fun startServer() {
        if (serverStarted) return
        serverStarted = true

        Log.d(TAG, "Szerver indítása WiFi + TCP üzemmódban")
        _status.value = "Szerver indítása..."

        // UDP szerver indítása (device discovery)
        startUdpServer()

        // TCP szerver indítása
        initTcpServer()
        tcpServer.start()

        // Hotspot név lekérdezése
        getHotspotName()

        // Hotspot monitor indítása (5 másodpercenként ellenőrizze az állapotot)
        startHotspotMonitor()
    }

    private fun startUdpServer() {
        isRunning = true
        Thread {
            try {
                udpServer = DatagramSocket(9998)
                Log.d(TAG, "✓ UDP szerver indult a 9998-as porton")

                while (isRunning && udpServer != null) {
                    val buffer = ByteArray(256)
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpServer?.receive(packet) ?: break

                    val message = String(packet.data, 0, packet.length).trim()
                    if (message == "FIND_SERVER") {
                        Log.d(TAG, "← Kliens keresése: ${packet.address}")

                        val myIp = getLocalIpAddress()
                        val response = "SERVER_HERE|$myIp"
                        val responseBytes = response.toByteArray()
                        val responsePacket = DatagramPacket(
                            responseBytes,
                            responseBytes.size,
                            packet.address,
                            packet.port
                        )
                        udpServer?.send(responsePacket)
                        Log.d(TAG, "→ Válasz: $myIp")
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "UDP szerver hiba", e)
                }
            } finally {
                udpServer?.close()
            }
        }.start()
    }

    private fun getLocalIpAddress(): String {
        return try {
            // 1. Próbálkozzunk a WiFi Manager-rel
            val wifiManager = getApplication<Application>()
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipAddress = wifiManager.connectionInfo.ipAddress
            if (ipAddress != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    (ipAddress shr 8) and 0xff,
                    (ipAddress shr 16) and 0xff,
                    (ipAddress shr 24) and 0xff
                )
            }

            // 2. Fallback: összes hálózati interfészt keresünk meg
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress
                        if (ip.isNotEmpty() && !ip.startsWith("127.")) {
                            Log.d(TAG, "✓ IP megtalálva: $ip (interfész: ${ni.name})")
                            return ip
                        }
                    }
                }
            }

            // 3. Utolsó fallback: hotspot default IP
            "192.168.43.1"
        } catch (e: Exception) {
            Log.e(TAG, "IP lekérdezés hiba", e)
            "192.168.43.1"
        }
    }

    private fun getHotspotName() {
        Thread {
            try {
                val ip = getLocalIpAddress()
                Log.d(TAG, "═══ HOTSPOT MONITOROZÁS ═══")
                Log.d(TAG, "IP: $ip")
                Log.d(TAG, "isRunning: $isRunning")
                Log.d(TAG, "tcpServer init: ${::tcpServer.isInitialized}")

                val wifiManager = getApplication<Application>()
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager

                // WifiManager információk
                try {
                    val connectionInfo = wifiManager.connectionInfo
                    Log.d(TAG, "WifiManager.getConnectionInfo():")
                    Log.d(TAG, "  - SSID: ${connectionInfo.ssid}")
                    Log.d(TAG, "  - BSSID: ${connectionInfo.bssid}")
                    Log.d(TAG, "  - Link Speed: ${connectionInfo.linkSpeed}")
                    Log.d(TAG, "  - IP: ${connectionInfo.ipAddress}")
                } catch (e: Exception) {
                    Log.e(TAG, "WifiManager.getConnectionInfo() hiba: ${e.message}")
                }

                // WiFi State alapján detektálás
                var wifiState = 4 // UNKNOWN
                var isWifiDisabled = false
                try {
                    wifiState = wifiManager.wifiState
                    Log.d(TAG, "WifiManager.getWifiState(): $wifiState")
                    // 0 = WIFI_STATE_DISABLING
                    // 1 = WIFI_STATE_DISABLED  ← Hotspot aktív = WiFi ki van kapcsolva!
                    // 2 = WIFI_STATE_ENABLING
                    // 3 = WIFI_STATE_ENABLED   ← WiFi bekapcsolt = Hotspot valószínűleg ki
                    // 4 = WIFI_STATE_UNKNOWN
                    isWifiDisabled = (wifiState == 0 || wifiState == 1) // DISABLING vagy DISABLED
                    Log.d(TAG, "WiFi letiltva (hotspot jele): $isWifiDisabled")
                } catch (e: Exception) {
                    Log.e(TAG, "WifiManager.getWifiState() hiba: ${e.message}")
                }

                // Csatlakoztatott kliensek száma
                val clientCount = if (::tcpServer.isInitialized) tcpServer.getClientCount() else 0
                Log.d(TAG, "Csatlakoztatott TCP kliensek: $clientCount")

                // Hotspot detektálása: WiFi letiltva VAGY van csatlakozó kliens
                val hasClients = clientCount > 0
                val shouldBeActive = isWifiDisabled || hasClients

                Log.d(TAG, "Hotspot detektálása: hasClients=$hasClients, shouldBeActive=$shouldBeActive")

                if (shouldBeActive) {
                    Log.d(TAG, "✓ HOTSPOT AKTÍV")
                    _hotspotActive.postValue(true)

                    // SSID lekérdezésének kísérlete - több módszerrel
                    var ssid = ""
                    val context = getApplication<Application>()

                    // 1. Próbálkozás: Különböző Settings Secure kulcsok
                    val possibleKeys = listOf(
                        "tether_ssid",           // Közös
                        "wifi_tether_ssid",      // Samsung
                        "tethering_ssid",        // Egyéb
                        "wifi_ap_ssid",          // Alternatív
                        "softap_ssid"            // Alternatív
                    )

                    for (key in possibleKeys) {
                        try {
                            val value = Settings.Secure.getString(context.contentResolver, key)
                            if (value != null && value.isNotEmpty() && !value.startsWith("<")) {
                                ssid = value
                                Log.d(TAG, "✓ SSID a Settings-ből (key: $key): $ssid")
                                break
                            } else {
                                Log.d(TAG, "→ Settings key '$key' üres vagy érvénytelen: $value")
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "→ Settings key '$key' lekérdezés hiba: ${e.message}")
                        }
                    }

                    // 2. Fallback: WifiManager.getConnectionInfo()
                    if (ssid.isEmpty()) {
                        try {
                            val rawSsid = wifiManager.connectionInfo.ssid
                            Log.d(TAG, "→ Raw SSID (WifiManager): $rawSsid")
                            ssid = rawSsid.replace("\"", "")
                            Log.d(TAG, "→ Cleaned SSID: $ssid")

                            if (ssid.isEmpty() || ssid.startsWith("<")) {
                                Log.d(TAG, "→ SSID érvénytelen")
                                ssid = ""
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "→ WifiManager SSID lekérdezés hiba: ${e.message}")
                        }
                    }

                    val displayName = if (ssid.isNotEmpty()) ssid else "Hotspot"
                    Log.d(TAG, "✓ Megjelenítendő név: $displayName")
                    _hotspotName.postValue(displayName)
                } else {
                    Log.d(TAG, "✗ HOTSPOT NINCS AKTÍV")
                    _hotspotActive.postValue(false)
                }

                Log.d(TAG, "═══════════════════════════")

            } catch (e: Exception) {
                Log.e(TAG, "HOTSPOT LEKÉRDEZÉS KRITIKUS HIBA: ${e.message}", e)
                _hotspotActive.postValue(false)
            }
        }.start()
    }

    /**
     * Hotspot monitor – 5 másodpercenként ellenőrzi az állapotot
     */
    private fun startHotspotMonitor() {
        viewModelScope.launch {
            while (isRunning) {
                try {
                    delay(5000) // 5 másodperc
                    getHotspotName()
                } catch (e: Exception) {
                    Log.e(TAG, "Hotspot monitor hiba", e)
                }
            }
        }
    }

    fun setHotspotInfo(ssid: String, password: String) {
        Log.d(TAG, "Hotspot adatok beállítva: $ssid")
        _hotspotName.postValue(ssid)
        _hotspotPassword.postValue(password)
        _hotspotActive.postValue(true)
    }

    fun openHotspotSettings() {
        val infoMsg = "A telefonod beállításaival injíts egy mobil hotspot internetmegosztást, a KLIENS telefonok kapcsolódjanak ehhez a hotspothoz"

        try {
            val app = getApplication<Application>()
            val intentsTried = mutableListOf<String>()

            // 1. Próbálkozzunk explicit TetherSettings Activity-vel (Samsung, Google, stb.)
            val tetherActivities = listOf(
                "com.android.settings/.TetherSettings",
                "com.android.settings/.network.TetherSettings",
                "android.settings.TETHERING_SETTINGS"
            )

            for (activity in tetherActivities) {
                try {
                    val intent = Intent().apply {
                        component = android.content.ComponentName(
                            activity.substringBefore("/"),
                            activity.substringAfter("/")
                        )
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    app.startActivity(intent)
                    Log.d(TAG, "TetherSettings megnyitva: $activity")
                    _status.postValue(infoMsg)
                    return
                } catch (e: Exception) {
                    intentsTried.add(activity)
                    Log.d(TAG, "Nem működik: $activity")
                }
            }

            // 2. Fallback: Network Settings (még közelebbi, mint általános Settings)
            val intent = Intent().apply {
                component = android.content.ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$NetworkDashboardActivity"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                app.startActivity(intent)
                Log.d(TAG, "Network Settings megnyitva")
                _status.postValue(infoMsg)
                return
            } catch (e: Exception) {
                Log.d(TAG, "Network Settings nem működik")
            }

            // 3. Utolsó fallback: általános Settings
            val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            app.startActivity(settingsIntent)
            Log.d(TAG, "Beállítások megnyitva")
            _status.postValue(infoMsg)

        } catch (e: Exception) {
            Log.e(TAG, "Hiba a beállítások megnyitásakor", e)
            _status.postValue(infoMsg)
        }
    }

    private fun initTcpServer() {
        tcpServer = TcpServer(
            port = 9999,
            onClientConnected = { clientName, deviceType, clientCount ->
                Log.d(TAG, "✓ WiFi kliens csatlakozva: $clientName | Típus: $deviceType (összesen: $clientCount)")
                // WiFi kliens hozzáadása a külön listához
                val wifiClient = WifiClient(name = clientName, type = deviceType)
                val list = _wifiClients.value.orEmpty().toMutableList()
                if (list.none { it.name == clientName }) {
                    list.add(wifiClient)
                    _wifiClients.postValue(list)
                }
                val totalClients = (_devices.value?.size ?: 0) + list.size
                _status.postValue("$totalClients telefon csatlakozva")
            },
            onClientDisconnected = { clientName, clientCount ->
                Log.d(TAG, "✗ WiFi kliens lecsatlakozott: $clientName")
                val list = _wifiClients.value.orEmpty().filterNot { it.name == clientName }
                _wifiClients.postValue(list)
                _status.postValue("$clientName lecsatlakozott")
            },
            onPhotoReceived = { clientName, fileName, bytes ->
                Log.d(TAG, "✓ Fotó fogadva: $fileName ($clientName, ${bytes.size} byte)")
                saveReceivedPhoto(fileName, bytes)
                val map = _transferStates.value.orEmpty().toMutableMap()
                map[clientName] = TransferState(TransferStatus.DONE, 100)
                _transferStates.postValue(map)
                _status.postValue("$clientName képe megérkezett ✓")

                // 3 másodperc után töröljük az üzenetet
                viewModelScope.launch {
                    delay(3000)
                    _status.postValue("")
                }
            },
            onProgressReceived = { clientName, fileName, progress ->
                Log.d(TAG, "↻ Feltöltés előrehaladása: $clientName - $progress%")
                val map = _transferStates.value.orEmpty().toMutableMap()
                map[clientName] = TransferState(TransferStatus.TRANSFERRING, progress)
                _transferStates.postValue(map)

                // Ha 100%, akkor 3 másodperc után vissza IDLE-re
                if (progress >= 100) {
                    viewModelScope.launch {
                        delay(3000)
                        val resetMap = _transferStates.value.orEmpty().toMutableMap()
                        resetMap[clientName] = TransferState(TransferStatus.IDLE, 0)
                        _transferStates.postValue(resetMap)
                    }
                }
            },
            onError = { message ->
                Log.e(TAG, "TCP szerver hiba: $message")
                _status.postValue("Szerver hiba: $message")
            }
        )
    }

    fun lockConnections() {
        _isLocked.value = true
        // btManager.lockConnections() // Bluetooth deaktiválva

        // WiFi klienteknek LOCK parancs
        if (::tcpServer.isInitialized) {
            Log.d(TAG, "LOCK parancs küldése WiFi klienseknek")
            tcpServer.broadcastMessage("LOCK")
        }

        _status.value = "Kamera aktív – készen áll a fotózásra"
    }

    fun prepareShoot(): String {
        sessionTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        photoCounter++
        val filename = "sts_${sessionTimestamp}_telefon1_${"%03d".format(photoCounter)}.jpg"

        // Bluetooth klienteknek (deaktiválva)
        // btManager.shootAll(sessionTimestamp, photoCounter)

        // WiFi klienteknek (TCP-n keresztül) – optimalizált delay Handler.postAtTime()-vel
        // 1000ms delay elég a pontosabb Handler.postAtTime() szinkronizációval
        if (::tcpServer.isInitialized) {
            Log.d(TAG, "SHOOT parancs küldése WiFi klienseknek (1000ms delay, postAtTime szinkronizáció)")
            val shootTime = System.currentTimeMillis() + 1000L
            tcpServer.broadcastMessage("SHOOT|$sessionTimestamp|${"%03d".format(photoCounter)}|$shootTime")
        }

        _status.value = "Fénykép készítése..."
        // Mindkét típusú kliens státusza
        val btMap = _devices.value.orEmpty().associate { it.name to TransferState(TransferStatus.SHOOTING, 0) }
        val wifiMap = _wifiClients.value.orEmpty().associate { it.name to TransferState(TransferStatus.SHOOTING, 0) }
        _transferStates.value = btMap + wifiMap
        return filename
    }

    fun onControllerPhotoSaved() {
        _status.postValue("Vezérlő képe mentve – fogadom a kliensek képeit...")
    }

    private fun saveReceivedPhoto(filename: String, bytes: ByteArray) {
        val context = getApplication<Application>()
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/sametimeshot")
        }
        runCatching {
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> os.write(bytes) } }
        }
    }

    fun disconnectAll() {
        Log.d(TAG, "Szerver leállítása")
        isRunning = false

        // UDP szerver leállítása
        udpServer?.close()

        // TCP szerver leállítása
        if (::tcpServer.isInitialized) {
            tcpServer.broadcastMessage("DISCONNECT")
            tcpServer.stop()
        }

        // Bluetooth leállítása (deaktiválva)
        // btManager.disconnectAll()

        _navigateToStart.value = true
    }

    fun onNavigatedToStart() { _navigateToStart.value = false }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel takarítás")

        isRunning = false

        // UDP szerver leállítása
        udpServer?.close()

        // Szerver leállítása
        if (::tcpServer.isInitialized) {
            tcpServer.stop()
        }

        // Bluetooth leállítása (deaktiválva)
        // btManager.cleanup()
    }
}
