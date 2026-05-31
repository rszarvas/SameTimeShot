package com.sametime.shot.model

/**
 * WiFi-n csatlakoztatott kliens modell
 * Eltér a Bluetooth ConnectedDevice-tól
 */
data class WifiClient(
    val name: String,
    var transferStatus: TransferStatus = TransferStatus.IDLE
)
