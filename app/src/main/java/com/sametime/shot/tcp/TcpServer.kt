package com.sametime.shot.tcp

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * TCP szerver a vezérlőn.
 * Fogadja a kliens kapcsolatokat és kezeli a fájlátvitelt.
 *
 * MINDEN ADATOT SZÖVEGES FORMÁTUMBAN FOGADUNK (Base64 fotók)!
 */
class TcpServer(
    private val port: Int = 9999,
    private val onClientConnected: (clientName: String, deviceType: String?, clientCount: Int) -> Unit = { _, _, _ -> },
    private val onClientDisconnected: (clientName: String, clientCount: Int) -> Unit = { _, _ -> },
    private val onPhotoReceived: (clientName: String, fileName: String, bytes: ByteArray) -> Unit = { _, _, _ -> },
    private val onProgressReceived: (clientName: String, fileName: String, progress: Int) -> Unit = { _, _, _ -> },
    private val onError: (message: String) -> Unit = {}
) {

    companion object {
        private const val TAG = "STS-TcpServer"
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val connectedClients = ConcurrentHashMap<String, ClientHandler>()
    private var clientCounter = 0

    /**
     * Szerver indítása
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Szerver már fut")
            return
        }

        try {
            // IPv4-en hallgatunk (0.0.0.0), nem IPv6-on
            serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
            isRunning = true
            Log.d(TAG, "✓ TCP szerver elindult a $port porton (IPv4: 0.0.0.0)")

            // Kliens elfogadása egy külön szálban
            kotlin.concurrent.thread {
                acceptConnections()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Hiba a szerver indítása közben", e)
            onError("Szerver indítási hiba: ${e.message}")
        }
    }

    /**
     * Szerver leállítása
     */
    fun stop() {
        if (!isRunning) return

        try {
            isRunning = false
            connectedClients.forEach { (_, handler) ->
                handler.disconnect()
            }
            connectedClients.clear()
            serverSocket?.close()
            Log.d(TAG, "✓ TCP szerver leállítva")
        } catch (e: Exception) {
            Log.e(TAG, "Hiba a szerver leállítása közben", e)
        }
    }

    /**
     * Kliens elfogadása
     */
    private fun acceptConnections() {
        while (isRunning) {
            try {
                Log.d(TAG, "⏳ Szerver vár a kliens csatlakozásara... serverSocket=$serverSocket, isRunning=$isRunning")
                val clientSocket = serverSocket?.accept() ?: continue
                clientCounter++
                val clientName = "Telefon-${clientCounter + 1}"

                Log.d(TAG, "→ Új kliens csatlakozva: $clientName")

                val handler = ClientHandler(clientSocket, clientName)
                connectedClients[clientName] = handler

                // Kliens kezelése egy külön szálban
                kotlin.concurrent.thread {
                    handler.handleClient()
                    connectedClients.remove(clientName)
                    onClientDisconnected(clientName, connectedClients.size)
                }

            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Hiba az elfogadás közben", e)
                }
            }
        }
    }

    /**
     * Szórásos üzenet küldése (pl. SHOOT parancs)
     */
    fun broadcastMessage(message: String) {
        Thread {
            connectedClients.forEach { (_, handler) ->
                handler.sendMessage(message)
            }
        }.start()
    }

    /**
     * Csatlakoztatott kliensek száma
     */
    fun getClientCount(): Int = connectedClients.size

    /**
     * Kliens kezelő (belső osztály)
     */
    private inner class ClientHandler(
        private val socket: Socket,
        private val clientName: String
    ) {
        private val reader = BufferedReader(InputStreamReader(socket.inputStream))
        private val writer = BufferedWriter(OutputStreamWriter(socket.outputStream))
        private var deviceType: String? = null

        fun sendMessage(message: String) {
            try {
                writer.write(message)
                writer.write("\n")
                writer.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Hiba az üzenet küldésekor ($clientName)", e)
            }
        }

        fun handleClient() {
            try {
                // HELLO üzenet fogadása (formátum: "HELLO|client|Samsung SM-A605FN")
                val hello = reader.readLine() ?: return
                Log.d(TAG, "← HELLO: $hello ($clientName)")

                // Telefon típusának kinyerése
                val helloParts = hello.split("|")
                deviceType = if (helloParts.size >= 3) helloParts[2] else null
                Log.d(TAG, "✓ Telefon típusa: $deviceType")

                // READY válasz
                sendMessage("READY|$clientName")

                // Callback a telefon típusával
                onClientConnected(clientName, deviceType, connectedClients.size)

                // Üzenetek fogadása
                while (isRunning && socket.isConnected) {
                    val line = reader.readLine() ?: break

                    when {
                        line.startsWith("PHOTO|") -> {
                            handlePhotoMessage(line)
                        }
                        line.startsWith("PROGRESS|") -> {
                            handleProgressMessage(line)
                        }
                        line == "DISCONNECT" -> {
                            Log.d(TAG, "← DISCONNECT ($clientName)")
                            break
                        }
                        else -> {
                            Log.d(TAG, "← Üzenet: $line ($clientName)")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Hiba a kliens kezelése közben ($clientName)", e)
            } finally {
                disconnect()
            }
        }

        private fun handlePhotoMessage(line: String) {
            try {
                val parts = line.split("|", limit = 4)
                if (parts.size < 4) {
                    Log.w(TAG, "⚠️ Hiányos PHOTO üzenet: $line")
                    return
                }

                val fileName = parts[1]
                val originalSize = parts[2].toInt()
                val base64Data = parts[3]

                Log.d(TAG, "← PHOTO fejléc: $fileName, $originalSize byte, Base64: ${base64Data.length} karakter")

                // Base64 dekódolás
                try {
                    val bytes = Base64.decode(base64Data, Base64.NO_WRAP)

                    if (bytes.size == originalSize) {
                        Log.d(TAG, "✓ Fotó fogadva: $fileName ($clientName, ${bytes.size} byte)")
                        onPhotoReceived(clientName, fileName, bytes)
                    } else {
                        Log.w(TAG, "⚠️ Méret eltérés: $fileName (${bytes.size}/${originalSize} byte)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "⚠️ Base64 dekódolási hiba: $fileName", e)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Hiba a fotó feldolgozása közben", e)
            }
        }

        private fun handleProgressMessage(line: String) {
            try {
                val parts = line.split("|", limit = 3)
                if (parts.size < 3) {
                    Log.w(TAG, "⚠️ Hiányos PROGRESS üzenet: $line")
                    return
                }

                val fileName = parts[1]
                val progress = parts[2].toIntOrNull() ?: 0

                Log.d(TAG, "← PROGRESS: $fileName ($progress%) ($clientName)")
                onProgressReceived(clientName, fileName, progress)

            } catch (e: Exception) {
                Log.e(TAG, "Hiba a progress feldolgozása közben", e)
            }
        }

        fun disconnect() {
            try {
                socket.close()
                Log.d(TAG, "✗ Kliens lecsatlakozva: $clientName")
            } catch (e: Exception) {
                Log.e(TAG, "Hiba a kliens lecsatlakozása közben", e)
            }
        }
    }
}
