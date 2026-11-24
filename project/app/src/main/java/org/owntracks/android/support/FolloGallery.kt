package org.owntracks.android.support

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * Copies a photo or video the app took into the phone's Gallery, DCIM/FOLLO Tracks (Alex 25 Nov 2026: "Save a copy to
 * my phone", after a volunteer found FOLLO Tracks videos never reach the Gallery). The copy is the camera's own file, byte for
 * byte; the app's copy and what basecamp has are not touched. Android 10+: MediaStore, no permission. Android 7–9:
 * the public DCIM folder + a media scan, needs WRITE_EXTERNAL_STORAGE (manifest maxSdkVersion 28; asked by the caller).
 * Blocking: call off the main thread.
 */
object FolloGallery {
  fun needsPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

  @Throws(IOException::class)
  fun save(context: Context, src: File, p: FolloPhoto) {
    if (!src.isFile) throw IOException("no original on this phone")
    val name = FolloPhotoRules.galleryName(p)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val video = p.type == "video"
      val coll =
          if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
          else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
      val r = context.contentResolver
      val cv =
          ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, p.oct)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + FolloPhotoRules.GALLERY_FOLDER)
            put(MediaStore.MediaColumns.DATE_TAKEN, p.t)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
          }
      val uri = r.insert(coll, cv) ?: throw IOException("the Gallery refused the file")
      try {
        (r.openOutputStream(uri) ?: throw IOException("cannot write to the Gallery")).use { out ->
          src.inputStream().use { it.copyTo(out, 1 shl 20) }
        }
        r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
      } catch (e: Exception) {
        runCatching { r.delete(uri, null, null) }
        throw (e as? IOException ?: IOException(e))
      }
    } else {
      @Suppress("DEPRECATION")
      val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), FolloPhotoRules.GALLERY_FOLDER)
      if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot make DCIM/${FolloPhotoRules.GALLERY_FOLDER}")
      var dst = File(dir, name)
      var n = 2
      while (dst.exists()) dst = File(dir, name.substringBeforeLast('.') + "_" + n++ + "." + name.substringAfterLast('.'))
      try {
        src.copyTo(dst)
      } catch (e: Exception) {
        dst.delete()
        throw (e as? IOException ?: IOException(e))
      }
      dst.setLastModified(p.t)
      MediaScannerConnection.scanFile(context, arrayOf(dst.path), arrayOf(p.oct), null)
    }
  }
}
