package dev.mobile.maestro

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.OutputStream

object MediaStorage {

    fun getOutputStream(context: Context, mediaName: String, mediaExt: String): OutputStream? {
        if (context.checkSelfPermission(WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Log.e("MediaStorage", "Permission $WRITE_EXTERNAL_STORAGE not granted")
            return null
        }
        val uri = when (mediaExt) {
            Service.FileType.JPG.ext,
            Service.FileType.PNG.ext,
            Service.FileType.GIF.ext,
            Service.FileType.JPEG.ext -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Service.FileType.MP4.ext -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> throw IllegalStateException("mime .$mediaExt not yet supported")
        }
        val ext = Service.FileType.values().first { it.ext == mediaExt }
        val contentValues = ContentValues()
        contentValues.apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, mediaName)
            put(MediaStore.MediaColumns.MIME_TYPE, ext.mimeType)
        }
        val contentResolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val outputStream = contentResolver.insert(uri, contentValues)?.let {
            contentResolver.openOutputStream(it)
        }
        return outputStream
    }
}