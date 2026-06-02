package com.sametime.shot.model

/**
 * WiFi-n csatlakoztatott kliens modell
 * Eltér a Bluetooth ConnectedDevice-tól
 */
data class WifiClient(
    val name: String,
    val type: String? = null,  // telefon típusa/modellje (WiFi klienseken nem mindig elérhető)
    var transferStatus: TransferStatus = TransferStatus.IDLE
)
