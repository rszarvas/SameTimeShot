package com.sametime.shot.ui.controller

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.sametime.shot.bluetooth.ControllerBtManager
import com.sametime.shot.model.ConnectedDevice
import com.sametime.shot.model.TransferState
import com.sametime.shot.model.TransferStatus
import com.sametime.shot.model.WifiClient
import com.sametime.shot.tcp.TcpServer
import com.sametime.shot.wifi.WifiHotspotManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.S) // Android 12+
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

    private var sessionTimestamp = ""
    private var photoCounter = 0
    private var serverStarted = false

    // WiFi + TCP komponensek
    private val wifiHotspotManager = WifiHotspotManager(application)
    private lateinit var tcpServer: TcpServer

    private val btAdapter: BluetoothAdapter by lazy {
        @SuppressLint("MissingPermission")
        val a = (application.getSystemService(BluetoothManager::class.java)).adapter; a
    }

    private val btManager by lazy {
        ControllerBtManager(
            adapter = btAdapter,
            onPhotoReceived = { filename, bytes -> saveReceivedPhoto(filename, bytes) },
            onDeviceConnected = { device ->
                val list = _devices.value.orEmpty().toMutableList().also { it.add(device) }
                _devices.postValue(list)
                _status.postValue("${list.size} telefon csatlakozva")
            },
            onDeviceDisconnected = { name ->
                val list = _devices.value.orEmpty().filterNot { it.name == name }
                _devices.postValue(list)
                _status.postValue("$name lecsatlakozott")
            },
            onTransferProgress = { name, status, progress ->
                val map = _transferStates.value.orEmpty().toMutableMap()
                map[name] = TransferState(status, progress)
                _transferStates.postValue(map)
                when (status) {
                    TransferStatus.DONE -> _status.postValue("$name képe megérkezett ✓")
                    TransferStatus.ERROR -> _status.postValue("$name küldési hiba!")
                    else -> {}
                }
            },
            onStatusChange = { msg -> _status.postValue(msg) }
        )
    }

    fun startServer() {
        if (serverStarted) return
        serverStarted = true

        Log.d(TAG, "Szerver indítása WiFi + TCP üzemmódban")
        _status.value = "WiFi hotspot indítása..."

        // 1. WiFi hotspot indítása
        wifiHotspotManager.startHotspot { success, message ->
            Log.d(TAG, "Hotspot callback: $message")
            _status.postValue(message)

            if (success) {
                // 2. TCP szerver indítása
                Log.d(TAG, "TCP szerver indítása...")
                initTcpServer()
                tcpServer.start()
            }
        }
    }

    private fun initTcpServer() {
        tcpServer = TcpServer(
            port = 9999,
            onClientConnected = { clientName, clientCount ->
                Log.d(TAG, "✓ WiFi kliens csatlakozva: $clientName (összesen: $clientCount)")
                // WiFi kliens hozzáadása a külön listához
                val wifiClient = WifiClient(name = clientName)
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
            },
            onError = { message ->
                Log.e(TAG, "TCP szerver hiba: $message")
                _status.postValue("Szerver hiba: $message")
            }
        )
    }

    fun lockConnections() {
        _isLocked.value = true
        btManager.lockConnections()
        _status.value = "Kamera aktív – készen áll a fotózásra"
    }

    fun prepareShoot(): String {
        sessionTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        photoCounter++
        val filename = "sts_${sessionTimestamp}_telefon1_${"%03d".format(photoCounter)}.jpg"

        // Bluetooth klienteknek
        btManager.shootAll(sessionTimestamp, photoCounter)

        // WiFi klienteknek (TCP-n keresztül)
        if (::tcpServer.isInitialized) {
            Log.d(TAG, "SHOOT parancs küldése WiFi klienseknek")
            tcpServer.broadcastMessage("SHOOT|$sessionTimestamp|${"%03d".format(photoCounter)}")
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
        Log.d(TAG, "Szerver leállítása és hotspot kikapcsolása")

        // TCP szerver leállítása
        if (::tcpServer.isInitialized) {
            tcpServer.broadcastMessage("DISCONNECT")
            tcpServer.stop()
        }

        // WiFi hotspot kikapcsolása
        wifiHotspotManager.stopHotspot { success, message ->
            Log.d(TAG, "Hotspot leállítása: $message")
        }

        // Bluetooth leállítása (fallback)
        btManager.disconnectAll()

        _navigateToStart.value = true
    }

    fun onNavigatedToStart() { _navigateToStart.value = false }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel takarítás")

        // Szerver leállítása
        if (::tcpServer.isInitialized) {
            tcpServer.stop()
        }

        // Hotspot kikapcsolása
        if (wifiHotspotManager.isHotspotActive()) {
            wifiHotspotManager.stopHotspot { _, _ -> }
        }

        // Bluetooth leállítása
        btManager.cleanup()
    }
}
