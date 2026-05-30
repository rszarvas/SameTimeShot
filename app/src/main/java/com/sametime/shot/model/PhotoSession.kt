package com.sametime.shot.model

import android.net.Uri

data class PhotoItem(
    val id: Long,           // MediaStore._ID – törléshez kell
    val filename: String,
    val uri: Uri
)

data class PhotoSession(
    val sessionId: String,
    val displayDate: String,
    val photos: List<PhotoItem>,
    val thumbnailUri: Uri?
)
