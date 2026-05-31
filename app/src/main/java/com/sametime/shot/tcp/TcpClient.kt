package com.sametime.shot.tcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * TCP kliens a klienseken.
 * Csatlakozik a szerverre és küld fotókat.
 */
class TcpClient(
    private val serverHost: String = "192.168.43.1",
    private val serverPort: Int = 9999,
    private val onConnected: (clientName: String) -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val onMessageReceived: (message: String) -> Unit = {},
    private val onError: (message: String) -> Unit = {}
) {

    companion object {
        private const val TAG = "STS-TcpClient"
        private const val CHUNK_SIZE = 16 * 1024 // 16KB
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var isConnected = false
    private var clientName: String = ""

    /**
     * Szerverre csatlakozás
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "Csatlakozás a szerverhez: $serverHost:$serverPort")

            socket = Socket(serverHost, serverPort)
            input = DataInputStream(socket!!.inputStream)
            output = DataOutputStream(socket!!.outputStream)
            reader = BufferedReader(InputStreamReader(socket!!.inputStream))
            writer = BufferedWriter(OutputStreamWriter(socket!!.outputStream))

            // HELLO üzenet küldése
            sendMessage("HELLO|client")

            // READY válasz fogadása
            val response = reader?.readLine()
            if (response?.startsWith("READY|") == true) {
                clientName = response.removePrefix("READY|")
                isConnected = true
                Log.d(TAG, "✓ Szerverre csatlakozva: $clientName")
                onConnected(clientName)

                // Üzenetek fogadása egy külön szálban
                kotlin.concurrent.thread {
                    receiveMessages()
                }

                true
            } else {
                Log.e(TAG, "✗ Nem várt válasz: $response")
                onError("Szerver válaszhiba")
                false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Hiba a csatlakozás közben", e)
            onError("Csatlakozási hiba: ${e.message}")
            false
        }
    }

    /**
     * Fotó küldése
     */
    suspend fun sendPhoto(fileName: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!isConnected) {
                Log.e(TAG, "Nincs szerverre csatlakozás")
                return@withContext false
            }

            // Protokoll: PHOTO|filename|size\n + binary data
            val message = "PHOTO|$fileName|${bytes.size}\n"
            writer?.write(message)
            writer?.flush()

            // Fájl adatok küldése chunks-ban
            var sent = 0
            while (sent < bytes.size) {
                val chunk = minOf(CHUNK_SIZE, bytes.size - sent)
                output?.write(bytes, sent, chunk)
                output?.flush()
                sent += chunk

                // Progress log minden 100KB után
                if (sent % (100 * 1024) == 0) {
                    Log.d(TAG, "Küldés alatt: $sent/${bytes.size} byte")
                }
            }

            Log.d(TAG, "✓ Fotó elküldve: $fileName (${bytes.size} byte)")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Hiba a fotó küldésekor", e)
            onError("Fotó küldési hiba: ${e.message}")
            false
        }
    }

    /**
     * Szerver üzenetei fogadása
     */
    private fun receiveMessages() {
        try {
            while (isConnected && socket?.isConnected == true) {
                val message = reader?.readLine() ?: break
                Log.d(TAG, "← Üzenet: $message")
                onMessageReceived(message)

                when {
                    message.startsWith("SHOOT") -> {
                        // SHOOT parancs: ideje készíteni egy fotót
                    }
                    message == "DISCONNECT" -> {
                        disconnect()
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hiba az üzenetek fogadása közben", e)
            onError("Üzenetfogadási hiba: ${e.message}")
        } finally {
            disconnect()
        }
    }

    /**
     * Szerverre csatlakozás lecsatlakoztatása
     */
    fun disconnect() {
        try {
            if (isConnected) {
                writer?.write("DISCONNECT\n")
                writer?.flush()
            }
            isConnected = false
            socket?.close()
            Log.d(TAG, "✓ Szerverről lecsatlakozva")
            onDisconnected()
        } catch (e: Exception) {
            Log.e(TAG, "Hiba a lecsatlakozás közben", e)
        }
    }

    /**
     * Csatlakozási állapot
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Kliens neve
     */
    fun getClientName(): String = clientName

    /**
     * Üzenet küldése (általános célú)
     */
    fun sendMessage(message: String) {
        try {
            writer?.write("$message\n")
            writer?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Hiba az üzenet küldésekor", e)
        }
    }
}
