package com.sametime.shot.camera

import android.content.ContentValues
import android.content.Context
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

class CameraHelper(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var toneGenerator: ToneGenerator

    companion object {
        private const val TAG = "STS-Camera"
    }

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (e: Exception) {
            Log.e(TAG, "ToneGenerator inicializálása sikertelen: ${e.message}")
        }
    }

    fun playShutterSound() {
        try {
            if (::toneGenerator.isInitialized) {
                // Kamera shutter-szerű hang: magas biep
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 150)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shutter hang lejátszása sikertelen: ${e.message}")
        }
    }

    fun startCamera(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        Log.d(TAG, "startCamera() hívva")
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture!!
                )
                Log.d(TAG, "Kamera sikeresen inicializálva")
            }.onFailure { e ->
                Log.e(TAG, "Kamera inicializálási hiba: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun takePhotoAndSave(filename: String, onDone: (success: Boolean) -> Unit) {
        Log.d(TAG, "takePhotoAndSave() hívva: $filename")
        val capture = imageCapture ?: run {
            Log.e(TAG, "imageCapture null! Kamera nincs inicializálva.")
            onDone(false)
            return
        }
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/sametimeshot"
            )
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.takePicture(
            opts,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(out: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Kép mentve: $filename, uri=${out.savedUri}")
                    // Shutter hang az exponálás pillanatában
                    playShutterSound()
                    onDone(true)
                }
                override fun onError(e: ImageCaptureException) {
                    Log.e(TAG, "Képmentési hiba: ${e.message}", e)
                    onDone(false)
                }
            }
        )
    }

    // A callback MINDIG a főszálon fut vissza, hogy LiveData.value = ... biztonságos legyen
    fun takePhotoBytes(onResult: (ByteArray?) -> Unit) {
        Log.d(TAG, "takePhotoBytes() hívva")
        val capture = imageCapture ?: run {
            Log.e(TAG, "imageCapture null! Kamera nincs inicializálva.")
            onResult(null)
            return
        }
        val mainExecutor = ContextCompat.getMainExecutor(context)

        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                Log.d(TAG, "Kép elkészítve – méret: ${image.planes[0].buffer.remaining()} bájt, format=${image.format}")
                // Shutter hang az exponálás pillanatában
                playShutterSound()
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                // Visszatérés a főszálra – így LiveData.value biztonságosan hívható
                mainExecutor.execute {
                    Log.d(TAG, "takePhotoBytes callback főszálon: ${bytes.size} bájt")
                    onResult(bytes)
                }
            }
            override fun onError(e: ImageCaptureException) {
                Log.e(TAG, "Képkészítési hiba (bytes): ${e.message}", e)
                mainExecutor.execute { onResult(null) }
            }
        })
    }

    fun shutdown() {
        Log.d(TAG, "CameraHelper leállítva")
        try {
            if (::toneGenerator.isInitialized) {
                toneGenerator.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "ToneGenerator felszabadítása sikertelen: ${e.message}")
        }
        executor.shutdown()
    }
}
