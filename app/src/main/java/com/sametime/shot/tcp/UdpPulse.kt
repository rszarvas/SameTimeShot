package com.sametime.shot.tcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UDP pulzálás (heartbeat) a szinkronizációhoz
 * A vezérlő 10ms-enként küld az aktuális timestampet az összes kliensnek
 * A kliensek figyelik ezt a pulse-t és szinkronizálnak vele
 */
class UdpPulse {
    companion object {
        private const val TAG = "STS-UdpPulse"
        const val PULSE_PORT = 9997
        const val PULSE_INTERVAL_MS = 10L  // 10ms-enként küld pulse
    }

    // === VEZÉRLŐ: UDP broadcast pulzálás ===
    class PulseServer {
        private var socket: DatagramSocket? = null
        private var pulseJob: Job? = null
        private val broadcastAddress = InetAddress.getByName("255.255.255.255")

        fun start(scope: kotlinx.coroutines.CoroutineScope) {
            Log.d(TAG, "UDP Pulse Server indítása (port $PULSE_PORT)")
            try {
                socket = DatagramSocket(PULSE_PORT)
                socket?.broadcast = true

                pulseJob = scope.launch {
                    withContext(Dispatchers.IO) {
                        while (true) {
                            try {
                                val timestamp = System.currentTimeMillis()
                                val message = "PULSE|$timestamp"
                                val data = message.toByteArray()
                                val packet = DatagramPacket(
                                    data, data.size,
                                    broadcastAddress, PULSE_PORT
                                )
                                socket?.send(packet)
                                Log.d(TAG, "PULSE küldve: $timestamp")
                                delay(PULSE_INTERVAL_MS)
                            } catch (e: Exception) {
                                Log.e(TAG, "Pulse küldési hiba: ${e.message}")
                                delay(100)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pulse Server hiba: ${e.message}")
            }
        }

        fun stop() {
            Log.d(TAG, "UDP Pulse Server leállítása")
            pulseJob?.cancel()
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Socket bezárása sikertelen: ${e.message}")
            }
        }
    }

    // === KLIENS: UDP pulse figyelés ===
    class PulseListener(
        val onPulse: (timestamp: Long) -> Unit,
        val scope: kotlinx.coroutines.CoroutineScope
    ) {
        private var socket: DatagramSocket? = null
        private var listenJob: Job? = null

        fun start() {
            Log.d(TAG, "UDP Pulse Listener indítása (port $PULSE_PORT)")
            try {
                socket = DatagramSocket(PULSE_PORT)

                listenJob = scope.launch {
                    withContext(Dispatchers.IO) {
                        val buffer = ByteArray(256)
                        while (true) {
                            try {
                                val packet = DatagramPacket(buffer, buffer.size)
                                socket?.receive(packet)
                                val message = String(packet.data, 0, packet.length)
                                if (message.startsWith("PULSE|")) {
                                    val timestamp = message.substring(6).toLongOrNull() ?: 0L
                                    if (timestamp > 0) {
                                        onPulse(timestamp)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Pulse vételi hiba: ${e.message}")
                                delay(100)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pulse Listener hiba: ${e.message}")
            }
        }

        fun stop() {
            Log.d(TAG, "UDP Pulse Listener leállítása")
            listenJob?.cancel()
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Socket bezárása sikertelen: ${e.message}")
            }
        }
    }
}
