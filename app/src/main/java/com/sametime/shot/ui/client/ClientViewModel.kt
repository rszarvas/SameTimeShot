package com.sametime.shot.ui.client

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.sametime.shot.bluetooth.ClientBtManager
import com.sametime.shot.tcp.TcpClient
import com.sametime.shot.wifi.WifiScanner
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

@RequiresApi(Build.VERSION_CODES.S) // Android 12+
class ClientViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "STS-ClientVM"
    }

    private val _myName = MutableLiveData("")
    val myName: LiveData<String> = _myName

    private val _isConnected = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected

    private val _isLocked = MutableLiveData(false)
    val isLocked: LiveData<Boolean> = _isLocked

    private val _status = MutableLiveData("Hotspot keresése...")
    val status: LiveData<String> = _status

    private val _discoveredDevices = MutableLiveData<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: LiveData<List<BluetoothDevice>> = _discoveredDevices

    private val _isDiscovering = MutableLiveData(false)
    val isDiscovering: LiveData<Boolean> = _isDiscovering

    private val _navigateToStart = MutableLiveData(false)
    val navigateToStart: LiveData<Boolean> = _navigateToStart

    private val _shootEvent = MutableLiveData<Pair<String, String>?>(null)
    val shootEvent: LiveData<Pair<String, String>?> = _shootEvent

    // Küldési progress: 0-100, -1 = hiba, null = nem aktív
    private val _sendProgress = MutableLiveData<Int?>(null)
    val sendProgress: LiveData<Int?> = _sendProgress

    // WiFi + TCP komponensek
    private val wifiScanner = WifiScanner(application)
    private var tcpClient: TcpClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingShootTime = 0L

    private val btAdapter: BluetoothAdapter by lazy {
        (application.getSystemService(BluetoothManager::class.java)).adapter
    }

    private var btManager: ClientBtManager? = null

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    device ?: return
                    val name = runCatching { device.name }.getOrNull() ?: return
                    if (name.isBlank()) return
                    val current = _discoveredDevices.value.orEmpty().toMutableList()
                    if (current.none { it.address == device.address }) {
                        current.add(device)
                        _discoveredDevices.postValue(current)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isDiscovering.postValue(false)
                    _status.postValue("Keresés kész – válasszon eszközt")
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            application.registerReceiver(discoveryReceiver, filter)
        }
    }

    fun openWifiSettings() {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            getApplication<Application>().startActivity(intent)
            Log.d(TAG, "WiFi beállítások megnyitva")
        } catch (e: Exception) {
            Log.e(TAG, "Hiba a WiFi beállítások megnyitásakor", e)
            _status.postValue("Nem sikerült megnyitni a WiFi beállítások")
        }
    }

    fun findAndConnectToServer() {
        Log.d(TAG, "Szerver keresésének indítása...")
        _status.value = "Szerver keresése..."

        Thread {
            try {
                val udpSocket = DatagramSocket()
                udpSocket.broadcast = true
                udpSocket.soTimeout = 5000 // 5 másodperc timeout

                // UDP broadcast küldés
                val message = "FIND_SERVER".toByteArray()
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(message, message.size, broadcastAddr, 9998)

                Log.d(TAG, "→ UDP broadcast küldés: FIND_SERVER")
                udpSocket.send(packet)

                // Válasz fogadása
                val buffer = ByteArray(256)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                udpSocket.receive(responsePacket)

                val response = String(responsePacket.data, 0, responsePacket.length).trim()
                Log.d(TAG, "← UDP válasz: $response")

                if (response.startsWith("SERVER_HERE|")) {
                    val serverIp = response.removePrefix("SERVER_HERE|")
                    Log.d(TAG, "✓ Szerver IP megtalálva: $serverIp")

                    // TCP csatlakozás az IP-hez
                    connectToServerViaIp(serverIp)
                } else {
                    Log.w(TAG, "Érvénytelen válasz: $response")
                    _status.postValue("Szerver nem válaszol")
                }

                udpSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Szerver keresési hiba", e)
                _status.postValue("Szerver keresési hiba: ${e.message}")
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        Log.d(TAG, "WiFi hotspot keresésének indítása")
        _status.value = "sametimeshot hálózat keresése..."
        _isDiscovering.value = true

        viewModelScope.launch {
            wifiScanner.scanNetworks { networks ->
                Log.d(TAG, "Talált hálózatok: $networks")
                // Local-Only Hotspot API automatikusan generál SSID-t: AndroidShare_XXXX
                val hasSameTimeShot = networks.any { it.startsWith("AndroidShare_") }
                if (hasSameTimeShot) {
                    val foundNetwork = networks.first { it.startsWith("AndroidShare_") }
                    Log.d(TAG, "✓ Hotspot hálózat található: $foundNetwork")
                    _status.postValue("Hotspot hálózat találva – csatlakozás...")
                    // Automatikus csatlakozás
                    connectToController(foundNetwork)
                } else {
                    Log.w(TAG, "Hotspot hálózat nem található")
                    _status.postValue("Hotspot hálózat nem található. Próbálja később.")
                    _isDiscovering.postValue(false)
                }
            }
        }
    }

    /**
     * Csatlakozás a szerverhez egy adott IP-n keresztül
     */
    private fun connectToServerViaIp(serverIp: String) {
        Log.d(TAG, "TCP csatlakozás: $serverIp:9999")
        _status.postValue("Csatlakozás a szerverhez...")

        initTcpClient(serverIp)
        viewModelScope.launch {
            val connected = tcpClient?.connect() ?: false
            if (connected) {
                Log.d(TAG, "✓ TCP-n csatlakozva")
            } else {
                Log.e(TAG, "✗ TCP csatlakozási hiba")
                _status.postValue("TCP csatlakozási hiba")
            }
        }
    }

    /**
     * Csatlakozás a vezérlőhöz WiFi hotspottal
     */
    private fun connectToController(hotspotSsid: String) {
        Log.d(TAG, "Csatlakozás a vezérlőhöz WiFi-n: $hotspotSsid")
        _status.value = "Hotspothoz csatlakozás..."

        // WiFi csatlakozás
        wifiScanner.connectToSameTimeShot(ssid = hotspotSsid, password = "sametimeshot123") { wifiSuccess, wifiMessage ->
            Log.d(TAG, "WiFi callback: $wifiMessage")
            _status.postValue(wifiMessage)

            if (wifiSuccess) {
                // TCP kliens inicializálása és csatlakozása
                Log.d(TAG, "TCP klienshez csatlakozás...")
                initTcpClient()
                viewModelScope.launch {
                    val connected = tcpClient?.connect() ?: false
                    if (connected) {
                        _isDiscovering.postValue(false)
                        Log.d(TAG, "✓ TCP-n csatlakozva")
                    } else {
                        Log.e(TAG, "✗ TCP csatlakozási hiba")
                        _status.postValue("TCP csatlakozási hiba")
                        _isDiscovering.postValue(false)
                    }
                }
            }
        }
    }

    /**
     * TCP kliens inicializálása
     */
    private fun initTcpClient(serverHost: String = "192.168.43.1") {
        tcpClient = TcpClient(
            serverHost = serverHost,
            serverPort = 9999,
            onConnected = { clientName ->
                Log.d(TAG, "✓ Szrverre csatlakozva: $clientName")
                _myName.postValue(clientName)
                _isConnected.postValue(true)
                _status.postValue("Csatlakozva: $clientName")
            },
            onDisconnected = {
                Log.d(TAG, "Szerverről lecsatlakozva")
                _isConnected.postValue(false)
                _isLocked.postValue(false)
                wifiScanner.disconnectAndRestorePrevious()
                _navigateToStart.postValue(true)
            },
            onMessageReceived = { message ->
                Log.d(TAG, "← Üzenet: $message")
                when {
                    message == "LOCK" -> {
                        Log.d(TAG, "✓ LOCK parancs megkapva – kamera képernyő")
                        _isLocked.postValue(true)
                        _status.postValue("Kamera aktív – várakozik a fotójelre")
                    }
                    message.startsWith("SHOOT|") -> {
                        val parts = message.split("|")
                        if (parts.size >= 4) {
                            val timestamp = parts[1]
                            val counter = parts[2]
                            val shootTime = parts[3].toLongOrNull() ?: 0L

                            Log.d(TAG, "SHOOT parancs: timestamp=$timestamp, counter=$counter, shootTime=$shootTime (Choreographer sync)")
                            _status.postValue("Fotó készítés – Choreographer szinkronizáció")

                            // Choreographer-hez kötött szinkronizáció a pontosabb exponáláshoz
                            pendingShootTime = shootTime
                            scheduleShootWithChoreographer(shootTime, Pair(timestamp, counter))
                        } else if (parts.size >= 3) {
                            // Régi formátum kompatibilitás
                            val timestamp = parts[1]
                            val counter = parts[2]
                            _shootEvent.postValue(Pair(timestamp, counter))
                        }
                    }
                    message == "DISCONNECT" -> {
                        tcpClient?.disconnect()
                    }
                }
            },
            onError = { errorMsg ->
                Log.e(TAG, "TCP hiba: $errorMsg")
                _status.postValue("Hiba: $errorMsg")
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        btAdapter.cancelDiscovery()
        btManager?.disconnect()
        btManager = ClientBtManager(
            adapter = btAdapter,
            onAssigned = { name -> _myName.postValue(name); _isConnected.postValue(true); _status.postValue("Csatlakozva: $name") },
            onLocked   = { _isLocked.postValue(true); _status.postValue("Kamera aktív – várja a vezérlőt") },
            onShoot    = { ts, counter -> _shootEvent.postValue(Pair(ts, counter)) },
            onDisconnected = { _isConnected.postValue(false); _isLocked.postValue(false); _navigateToStart.postValue(true) },
            onStatus   = { msg -> _status.postValue(msg) },
            onSendProgress = { progress ->
                _sendProgress.postValue(progress)
                if (progress >= 100 || progress < 0) {
                    // Rövid késleltetés után elrejtjük
                    _sendProgress.postValue(null)
                }
            }
        )
        btManager!!.connect(device)
        _status.value = "Csatlakozás folyamatban..."
    }

    fun sendPhoto(filename: String, bytes: ByteArray) {
        Log.d(TAG, "Fotó küldésének indítása: $filename (${bytes.size} byte)")
        _sendProgress.value = 0

        // TCP-n keresztül küldünk
        if (tcpClient?.isConnected() == true) {
            Log.d(TAG, "TCP-n keresztüli fotó küldése")
            viewModelScope.launch {
                val success = tcpClient?.sendPhoto(filename, bytes) ?: false
                if (success) {
                    Log.d(TAG, "✓ Fotó sikeresen elküldve")
                    _sendProgress.postValue(100)
                    _status.postValue("Fotó elküldve")
                } else {
                    Log.e(TAG, "✗ Fotó küldési hiba")
                    _sendProgress.postValue(-1)
                    _status.postValue("Fotó küldési hiba")
                }
            }
        } else {
            // Fallback: Bluetooth (ha még aktív)
            Log.w(TAG, "TCP nincs csatlakozva, Bluetooth fallback")
            btManager?.sendPhoto(filename, bytes)
        }
    }

    fun onShootHandled() { _shootEvent.value = null }
    fun onNavigatedToStart() { _navigateToStart.value = false }

    /**
     * Busy-wait szinkronizáció az utolsó milliszekundumban
     * Az utolsó 20-30ms-ben aktívan várunk az exponálásra, nem callback-re
     */
    private fun scheduleShootWithChoreographer(shootTime: Long, shootData: Pair<String, String>) {
        val now = System.currentTimeMillis()
        val delayMs = shootTime - now

        if (delayMs <= 0) {
            // Azonnal
            Log.d(TAG, "SHOOT azonnal (delay <= 0)")
            _shootEvent.postValue(shootData)
        } else if (delayMs <= 30) {
            // Nagyon rövid delay - busy-wait az exponálásra
            Log.d(TAG, "SHOOT busy-wait: delayMs=$delayMs")
            viewModelScope.launch {
                // Busy-wait az utolsó milliszekundumig
                val startNano = System.nanoTime()
                val delayNano = delayMs * 1_000_000L
                while (System.nanoTime() - startNano < delayNano) {
                    // Aktív várakozás
                }
                Log.d(TAG, "Busy-wait vége – exponálás now!")
                _shootEvent.postValue(shootData)
            }
        } else if (delayMs <= 100) {
            // Rövid delay - postDelayed + busy-wait az utolsó 20ms-ben
            Log.d(TAG, "SHOOT postDelayed + busy-wait: delayMs=$delayMs")
            mainHandler.postDelayed({
                // Busy-wait az utolsó 20ms-ben
                val shootTimeNano = shootTime * 1_000_000L
                val nowNano = System.currentTimeMillis() * 1_000_000L
                val remainNano = shootTimeNano - nowNano
                if (remainNano > 0) {
                    val startNano = System.nanoTime()
                    while (System.nanoTime() - startNano < remainNano) {
                        // Aktív várakozás
                    }
                }
                Log.d(TAG, "Busy-wait vége – exponálás now!")
                _shootEvent.postValue(shootData)
            }, (delayMs - 20).coerceAtLeast(0L))
        } else {
            // Hosszabb delay - postDelayed a shootTime előtt 20ms-el, majd busy-wait
            Log.d(TAG, "SHOOT postDelayed + busy-wait: delayMs=$delayMs")
            mainHandler.postDelayed({
                // Busy-wait az utolsó 20ms-ben
                val shootTimeNano = shootTime * 1_000_000L
                val startNano = System.nanoTime()
                val targetNano = startNano + (shootTimeNano - System.currentTimeMillis() * 1_000_000L)
                while (System.nanoTime() < targetNano) {
                    // Aktív várakozás
                }
                Log.d(TAG, "Busy-wait vége – exponálás most!")
                _shootEvent.postValue(shootData)
            }, (delayMs - 20).coerceAtLeast(0L))
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel takarítás")

        // Handler callback-ek törlése
        mainHandler.removeCallbacksAndMessages(null)

        // TCP szever lecsatlakoztatása
        tcpClient?.disconnect()

        // WiFi lecsatlakoztatása és visszacsatlakozás az előző hálózathoz
        wifiScanner.disconnectAndRestorePrevious()

        // Bluetooth lecsatlakoztatása
        runCatching { getApplication<Application>().unregisterReceiver(discoveryReceiver) }
        btManager?.disconnect()
    }
}
