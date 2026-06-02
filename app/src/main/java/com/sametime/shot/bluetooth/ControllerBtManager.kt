package com.sametime.shot.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.sametime.shot.model.ConnectedDevice
import com.sametime.shot.model.TransferStatus
import kotlinx.coroutines.*
import java.io.IOException

@SuppressLint("MissingPermission")
class ControllerBtManager(
    private val adapter: BluetoothAdapter,
    private val onPhotoReceived: (filename: String, bytes: ByteArray) -> Unit,
    private val onDeviceConnected: (device: ConnectedDevice) -> Unit,
    private val onDeviceDisconnected: (name: String) -> Unit,
    private val onTransferProgress: (name: String, status: TransferStatus, progress: Int) -> Unit,
    private val onStatusChange: (String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: BluetoothServerSocket? = null
    private val clients = mutableListOf<ConnectedDevice>()
    private var nextPhoneNumber = 2
    private var accepting = false

    companion object {
        private const val TAG = "STS-ControllerBT"
        private const val CHUNK = 16384  // 16 KB olvasási darab
    }

    fun startAccepting() {
        accepting = true
        Log.d(TAG, "Bluetooth szerver indítása")
        scope.launch {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                    BtProtocol.APP_NAME, BtProtocol.APP_UUID
                )
                while (accepting && clients.size < 7) {
                    val socket = try {
                        serverSocket!!.accept(10_000)
                    } catch (e: IOException) {
                        if (accepting) continue else break
                    }
                    Log.d(TAG, "Új kapcsolat: ${socket.remoteDevice.address}")
                    handleNewClient(socket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Szerver hiba: ${e.message}", e)
                onStatusChange("Szerver hiba: ${e.message}")
            }
        }
    }

    private fun handleNewClient(socket: BluetoothSocket) {
        scope.launch {
            val phoneName = "telefon$nextPhoneNumber"
            nextPhoneNumber++
            val device = ConnectedDevice(
                name = phoneName,
                socket = socket,
                outputStream = socket.outputStream,
                inputStream = socket.inputStream,
                type = getDeviceType(socket.remoteDevice.name)
            )
            val line = BtProtocol.readLine(device.inputStream)
            if (line == null || !line.startsWith(BtProtocol.CMD_HELLO)) {
                Log.w(TAG, "Hibás handshake: '$line'")
                socket.close()
                return@launch
            }
            BtProtocol.writeLine(device.outputStream, "${BtProtocol.CMD_ASSIGNED}|$phoneName")
            Log.d(TAG, "ASSIGNED|$phoneName elküldve")
            synchronized(clients) { clients.add(device) }
            onDeviceConnected(device)
            startReadingFromClient(device)
        }
    }

    private fun startReadingFromClient(device: ConnectedDevice) {
        scope.launch {
            try {
                while (device.socket.isConnected) {
                    val line = BtProtocol.readLine(device.inputStream) ?: break
                    Log.d(TAG, "${device.name} → $line")
                    val (cmd, args) = BtProtocol.parseCommand(line)
                    if (cmd == BtProtocol.CMD_PHOTO) handlePhotoFromClient(device, args)
                }
            } catch (e: Exception) {
                Log.e(TAG, "${device.name} olvasási hiba: ${e.message}")
            } finally {
                Log.d(TAG, "${device.name} lecsatlakozott")
                synchronized(clients) { clients.remove(device) }
                onDeviceDisconnected(device.name)
                runCatching { device.socket.close() }
            }
        }
    }

    private suspend fun handlePhotoFromClient(device: ConnectedDevice, args: List<String>) {
        if (args.size < 2) return
        val filename = args[0]
        val size = args[1].toLongOrNull()?.toInt() ?: return
        Log.d(TAG, "${device.name}: kép fogadása – $filename ($size bájt)")
        onTransferProgress(device.name, TransferStatus.TRANSFERRING, 0)

        val buffer = ByteArray(size)
        var received = 0
        var lastReportedProgress = -1

        while (received < size) {
            val toRead = minOf(CHUNK, size - received)
            val read = device.inputStream.read(buffer, received, toRead)
            if (read == -1) { Log.e(TAG, "${device.name}: stream vége"); break }
            received += read

            val progress = received * 100 / size
            if (progress != lastReportedProgress) {
                lastReportedProgress = progress
                onTransferProgress(device.name, TransferStatus.TRANSFERRING, progress)
            }
        }

        if (received == size) {
            Log.d(TAG, "${device.name}: kép sikeresen fogadva ($received bájt)")
            onPhotoReceived(filename, buffer)
            BtProtocol.writeLine(device.outputStream, BtProtocol.CMD_PHOTO_ACK)
            onTransferProgress(device.name, TransferStatus.DONE, 100)
        } else {
            Log.e(TAG, "${device.name}: hiányos kép ($received/$size)")
            onTransferProgress(device.name, TransferStatus.ERROR, received * 100 / size)
        }
    }

    fun lockConnections() {
        accepting = false
        runCatching { serverSocket?.close() }
        broadcast(BtProtocol.CMD_LOCK)
    }

    fun shootAll(sessionTimestamp: String, counter: Int) {
        broadcast("${BtProtocol.CMD_SHOOT}|$sessionTimestamp|${"%03d".format(counter)}")
    }

    fun disconnectAll() {
        broadcast(BtProtocol.CMD_DISCONNECT)
        scope.launch {
            delay(600)
            synchronized(clients) {
                clients.forEach { runCatching { it.socket.close() } }
                clients.clear()
            }
        }
    }

    private fun broadcast(command: String) {
        scope.launch {
            synchronized(clients) { clients.toList() }.forEach { device ->
                runCatching { BtProtocol.writeLine(device.outputStream, command) }
                    .onFailure { Log.e(TAG, "${device.name}: broadcast hiba: ${it.message}") }
            }
        }
    }

    fun cleanup() {
        accepting = false
        scope.cancel()
        runCatching { serverSocket?.close() }
        synchronized(clients) {
            clients.forEach { runCatching { it.socket.close() } }
            clients.clear()
        }
    }

    /**
     * Telefon típusának lekérdezése
     * Ha a BT eszköz neve tartalmaz típus infót (pl. "Samsung SM-A605FN"), azt használjuk
     */
    private fun getDeviceType(deviceName: String?): String? {
        return if (deviceName.isNullOrBlank()) {
            null
        } else {
            // Ha van már típus info az eszköznévben (pl. "Samsung SM-A605FN")
            deviceName
        }
    }
}
