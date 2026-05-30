package com.sametime.shot.model

import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream

data class ConnectedDevice(
    val name: String,
    val socket: BluetoothSocket,
    val outputStream: OutputStream,
    val inputStream: InputStream,
    var transferStatus: TransferStatus = TransferStatus.IDLE
)

enum class TransferStatus { IDLE, SHOOTING, TRANSFERRING, DONE, ERROR }

data class TransferState(
    val status: TransferStatus = TransferStatus.IDLE,
    val progress: Int = 0   // 0-100
)
