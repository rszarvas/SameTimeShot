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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.sametime.shot.bluetooth.ClientBtManager

class ClientViewModel(application: Application) : AndroidViewModel(application) {

    private val _myName = MutableLiveData("")
    val myName: LiveData<String> = _myName

    private val _isConnected = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected

    private val _isLocked = MutableLiveData(false)
    val isLocked: LiveData<Boolean> = _isLocked

    private val _status = MutableLiveData("Eszközök keresése...")
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

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (!btAdapter.isEnabled) { _status.postValue("Bluetooth nincs bekapcsolva"); return }
        _discoveredDevices.value = emptyList()
        _isDiscovering.value = true
        _status.value = "Eszközök keresése..."
        btAdapter.startDiscovery()
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
        _sendProgress.value = 0
        btManager?.sendPhoto(filename, bytes)
    }

    fun onShootHandled() { _shootEvent.value = null }
    fun onNavigatedToStart() { _navigateToStart.value = false }

    override fun onCleared() {
        super.onCleared()
        runCatching { getApplication<Application>().unregisterReceiver(discoveryReceiver) }
        btManager?.disconnect()
    }
}
