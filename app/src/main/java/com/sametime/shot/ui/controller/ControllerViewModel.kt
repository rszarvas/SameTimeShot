package com.sametime.shot.ui.controller

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.sametime.shot.bluetooth.ControllerBtManager
import com.sametime.shot.model.ConnectedDevice
import com.sametime.shot.model.TransferState
import com.sametime.shot.model.TransferStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ControllerViewModel(application: Application) : AndroidViewModel(application) {

    private val _devices = MutableLiveData<List<ConnectedDevice>>(emptyList())
    val devices: LiveData<List<ConnectedDevice>> = _devices

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
        btManager.startAccepting()
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
        btManager.shootAll(sessionTimestamp, photoCounter)
        _status.value = "Fénykép készítése..."
        val map = _devices.value.orEmpty().associate { it.name to TransferState(TransferStatus.SHOOTING, 0) }
        _transferStates.value = map
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
        btManager.disconnectAll()
        _navigateToStart.value = true
    }

    fun onNavigatedToStart() { _navigateToStart.value = false }

    override fun onCleared() {
        super.onCleared()
        btManager.cleanup()
    }
}
