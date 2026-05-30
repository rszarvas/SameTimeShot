package com.sametime.shot.ui.gallery

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.sametime.shot.model.PhotoItem
import com.sametime.shot.model.PhotoSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val _sessions = MutableLiveData<List<PhotoSession>>(emptyList())
    val sessions: LiveData<List<PhotoSession>> = _sessions

    fun loadPhotos() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED
            )
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%sametimeshot%")
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )

            val photos = mutableListOf<PhotoItem>()
            cursor?.use { c ->
                val idCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id   = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: continue
                    if (!name.startsWith("sts_")) continue
                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                    )
                    photos.add(PhotoItem(id, name, uri))
                }
            }

            val grouped = photos.groupBy { extractSessionId(it.filename) }
            val sessions = grouped.map { (sessionId, items) ->
                val sorted = items.sortedBy { photoSortKey(it.filename) }
                PhotoSession(
                    sessionId    = sessionId,
                    displayDate  = formatSessionId(sessionId),
                    photos       = sorted,
                    thumbnailUri = sorted.firstOrNull()?.uri
                )
            }.sortedByDescending { it.sessionId }

            _sessions.postValue(sessions)
        }
    }

    fun deleteSession(session: PhotoSession) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            session.photos.forEach { photo ->
                runCatching {
                    context.contentResolver.delete(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Images.Media._ID} = ?",
                        arrayOf(photo.id.toString())
                    )
                }
            }
            loadPhotos()  // lista frissítése
        }
    }

    private fun extractSessionId(filename: String): String {
        val parts = filename.removePrefix("sts_").removeSuffix(".jpg").split("_")
        return if (parts.size >= 2) "${parts[0]}_${parts[1]}" else filename
    }

    private fun photoSortKey(filename: String): String {
        val parts = filename.removePrefix("sts_").removeSuffix(".jpg").split("_")
        return if (parts.size >= 4) "${parts[2]}_${parts[3]}" else filename
    }

    private fun formatSessionId(sessionId: String): String {
        return runCatching {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val date = sdf.parse(sessionId)!!
            SimpleDateFormat("yyyy.MM.dd  HH:mm:ss", Locale.getDefault()).format(date)
        }.getOrDefault(sessionId)
    }
}
