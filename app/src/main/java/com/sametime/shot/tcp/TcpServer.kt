package com.sametime.shot.tcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * TCP szerver a vezérlőn.
 * Fogadja a kliens kapcsolatokat és kezeli a fájlátvitelt.
 */
class TcpServer(
    private val port: Int = 9999,
    private val onClientConnected: (clientName: String, clientCount: Int) -> Unit = { _, _ -> },
    private val onClientDisconnected: (clientName: String, clientCount: Int) -> Unit = { _, _ -> },
    private val onPhotoReceived: (clientName: String, fileName: String, bytes: ByteArray) -> Unit = { _, _, _ -> },
    private val onError: (message: String) -> Unit = {}
) {

    companion object {
        private const val TAG = "STS-TcpServer"
        private const val CHUNK_SIZE = 16 * 1024 // 16KB
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
            serverSocket = ServerSocket(port)
            isRunning = true
            Log.d(TAG, "✓ TCP szerver elindult a $port porton")

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
                val clientSocket = serverSocket?.accept() ?: continue
                clientCounter++
                val clientName = "telefon${clientCounter + 1}"

                Log.d(TAG, "→ Új kliens csatlakozva: $clientName")

                val handler = ClientHandler(clientSocket, clientName)
                connectedClients[clientName] = handler

                onClientConnected(clientName, connectedClients.size)

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
        connectedClients.forEach { (_, handler) ->
            handler.sendMessage(message)
        }
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
        private val input = DataInputStream(socket.inputStream)
        private val output = DataOutputStream(socket.outputStream)
        private val reader = BufferedReader(InputStreamReader(socket.inputStream))
        private val writer = BufferedWriter(OutputStreamWriter(socket.outputStream))

        fun sendMessage(message: String) {
            try {
                writer.write(message)
                writer.write("\n")
                writer.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Hiba az üzenet küldésekor ($clientName)", e)
            }
        }

        fun sendPhoto(fileName: String, bytes: ByteArray) {
            try {
                // Protokoll: PHOTO|filename|size|data
                val message = "PHOTO|$fileName|${bytes.size}\n"
                writer.write(message)
                writer.flush()

                // Fájl adatok küldése chunks-ban
                var sent = 0
                while (sent < bytes.size) {
                    val chunk = minOf(CHUNK_SIZE, bytes.size - sent)
                    output.write(bytes, sent, chunk)
                    output.flush()
                    sent += chunk
                }

                Log.d(TAG, "✓ Fotó elküldve: $fileName ($clientName)")
            } catch (e: Exception) {
                Log.e(TAG, "Hiba a fotó küldésekor ($clientName)", e)
            }
        }

        fun handleClient() {
            try {
                // HELLO üzenet fogadása
                val hello = reader.readLine() ?: return
                Log.d(TAG, "← HELLO: $hello ($clientName)")

                // READY válasz
                sendMessage("READY|$clientName")

                // Üzenetek fogadása
                while (isRunning && socket.isConnected) {
                    val line = reader.readLine() ?: break

                    when {
                        line.startsWith("PHOTO|") -> {
                            handlePhotoMessage(line)
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
                val parts = line.split("|")
                if (parts.size < 3) return

                val fileName = parts[1]
                val fileSize = parts[2].toInt()

                // Fájl adatok fogadása
                val bytes = ByteArray(fileSize)
                var received = 0

                while (received < fileSize) {
                    val chunk = input.read(bytes, received, fileSize - received)
                    if (chunk == -1) break
                    received += chunk
                }

                if (received == fileSize) {
                    Log.d(TAG, "✓ Fotó fogadva: $fileName ($clientName, $fileSize byte)")
                    onPhotoReceived(clientName, fileName, bytes)
                } else {
                    Log.w(TAG, "⚠️ Hiányos fotó: $fileName (${received}/${fileSize} byte)")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Hiba a fotó feldolgozása közben", e)
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
