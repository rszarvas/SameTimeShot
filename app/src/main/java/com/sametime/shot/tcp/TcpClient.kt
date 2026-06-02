package com.sametime.shot.tcp

import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * TCP kliens a klienseken.
 * Csatlakozik a szerverre és küld fotókat.
 *
 * MINDEN ADATOT SZÖVEGES FORMÁTUMBAN KÜLDÜNK (Base64 fotók)!
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
    }

    private var socket: Socket? = null
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
            Log.d(TAG, "✓ Socket sikeresen létrehozva: $socket")

            reader = BufferedReader(InputStreamReader(socket!!.inputStream))
            writer = BufferedWriter(OutputStreamWriter(socket!!.outputStream))

            // HELLO üzenet küldése telefon típusával
            val deviceType = "${Build.MANUFACTURER} ${Build.MODEL}"
            sendMessage("HELLO|client|$deviceType")
            Log.d(TAG, "Telefon típusa küldve: $deviceType")

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
     * Fotó küldése Base64-ként
     */
    suspend fun sendPhoto(fileName: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!isConnected) {
                Log.e(TAG, "Nincs szerverre csatlakozás")
                return@withContext false
            }

            Log.d(TAG, "→ Fotó küldésének kezdete: $fileName (${bytes.size} byte)")

            // Protokoll: PHOTO|filename|originalSize|base64data\n
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            Log.d(TAG, "→ Base64 kódolás kész: ${base64.length} karakter")

            val message = "PHOTO|$fileName|${bytes.size}|$base64\n"

            synchronized(socket!!) {
                Log.d(TAG, "→ PHOTO üzenet küldése")
                writer?.write(message)
                writer?.flush()
                Log.d(TAG, "✓ Fotó elküldve: $fileName (${bytes.size} byte)")
            }

            // 100% progress küldése (teljes)
            sendMessage("PROGRESS|$fileName|100")

            true

        } catch (e: Exception) {
            Log.e(TAG, "Hiba a fotó küldésekor", e)
            Log.e(TAG, "Stack trace:", e)
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
                try {
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
                } catch (e: Exception) {
                    Log.e(TAG, "Hiba egy üzenet fogadásakor", e)
                    if (isConnected && socket?.isConnected == true) {
                        Log.d(TAG, "Próba helyreállítás az üzenet olvasásakor")
                        Thread.sleep(100)
                    } else {
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
        Thread {
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
        }.start()
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
