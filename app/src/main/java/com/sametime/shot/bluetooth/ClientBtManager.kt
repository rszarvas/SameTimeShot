package com.sametime.shot.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException

@SuppressLint("MissingPermission")
class ClientBtManager(
    private val adapter: BluetoothAdapter,
    private val onAssigned: (name: String) -> Unit,
    private val onLocked: () -> Unit,
    private val onShoot: (sessionTimestamp: String, counter: String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onSendProgress: (progress: Int) -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: BluetoothSocket? = null
    var myName: String = ""; private set

    companion object {
        private const val TAG = "STS-ClientBT"
        private const val CHUNK = 16384  // 16 KB küldési darab
    }

    fun connect(device: BluetoothDevice) {
        scope.launch {
            onStatus("Csatlakozás...")
            Log.d(TAG, "Csatlakozás: ${device.name} (${device.address})")
            try {
                adapter.cancelDiscovery()
                val sock = device.createRfcommSocketToServiceRecord(BtProtocol.APP_UUID)
                sock.connect()
                socket = sock
                Log.d(TAG, "Kapcsolat létrejött")
                BtProtocol.writeLine(sock.outputStream, BtProtocol.CMD_HELLO)
                startReading(sock)
            } catch (e: IOException) {
                Log.e(TAG, "Csatlakozási hiba: ${e.message}", e)
                onStatus("Csatlakozás sikertelen: ${e.message}")
                onDisconnected()
            }
        }
    }

    private fun startReading(sock: BluetoothSocket) {
        scope.launch {
            try {
                while (sock.isConnected) {
                    val line = BtProtocol.readLine(sock.inputStream) ?: break
                    Log.d(TAG, "Parancs érkezett: $line")
                    val (cmd, args) = BtProtocol.parseCommand(line)
                    when (cmd) {
                        BtProtocol.CMD_ASSIGNED -> {
                            myName = args.getOrElse(0) { "ismeretlen" }
                            Log.d(TAG, "Név kiosztva: $myName")
                            onAssigned(myName)
                        }
                        BtProtocol.CMD_LOCK   -> { Log.d(TAG, "LOCK"); onLocked() }
                        BtProtocol.CMD_SHOOT  -> {
                            val ts = args.getOrElse(0) { "" }
                            val counter = args.getOrElse(1) { "001" }
                            Log.d(TAG, "SHOOT: ts=$ts counter=$counter")
                            onShoot(ts, counter)
                        }
                        BtProtocol.CMD_DISCONNECT -> { Log.d(TAG, "DISCONNECT"); onDisconnected(); break }
                        else -> Log.w(TAG, "Ismeretlen parancs: $cmd")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Olvasási hiba: ${e.message}", e)
                onDisconnected()
            } finally {
                runCatching { sock.close() }
            }
        }
    }

    fun sendPhoto(filename: String, jpegBytes: ByteArray) {
        scope.launch {
            val sock = socket ?: run { Log.e(TAG, "sendPhoto: socket null"); return@launch }
            try {
                Log.d(TAG, "Kép küldése: $filename (${jpegBytes.size} bájt)")
                onSendProgress(0)

                // Fejléc: PHOTO|fájlnév|méret
                BtProtocol.writeLine(sock.outputStream, "${BtProtocol.CMD_PHOTO}|$filename|${jpegBytes.size}")

                // Chunked küldés, visszajelzéssel
                var sent = 0
                while (sent < jpegBytes.size) {
                    val end = minOf(sent + CHUNK, jpegBytes.size)
                    sock.outputStream.write(jpegBytes, sent, end - sent)
                    sent = end
                    val progress = sent * 100 / jpegBytes.size
                    onSendProgress(progress)
                }
                sock.outputStream.flush()
                Log.d(TAG, "Kép sikeresen elküldve: $filename")
                onStatus("Kép elküldve: $filename")
                onSendProgress(100)
            } catch (e: Exception) {
                Log.e(TAG, "Képküldési hiba: ${e.message}", e)
                onStatus("Képküldés sikertelen: ${e.message}")
                onSendProgress(-1) // hiba jelzése
            }
        }
    }

    fun disconnect() {
        runCatching { socket?.close() }
        socket = null
        scope.cancel()
    }
}
