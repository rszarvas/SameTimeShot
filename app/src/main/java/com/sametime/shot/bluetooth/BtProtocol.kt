package com.sametime.shot.bluetooth

import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

object BtProtocol {
    const val APP_NAME = "SameTimeShot"
    val APP_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")

    const val CMD_HELLO = "HELLO"
    const val CMD_ASSIGNED = "ASSIGNED"
    const val CMD_LOCK = "LOCK"
    const val CMD_SHOOT = "SHOOT"
    const val CMD_PHOTO = "PHOTO"
    const val CMD_PHOTO_ACK = "PHOTO_ACK"
    const val CMD_DISCONNECT = "DISCONNECT"

    fun parseCommand(line: String): Pair<String, List<String>> {
        val parts = line.trim().split("|")
        return Pair(parts[0], parts.drop(1))
    }

    fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) return sb.toString().trim()
            sb.append(b.toChar())
        }
    }

    fun writeLine(output: OutputStream, line: String) {
        output.write((line + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }
}
