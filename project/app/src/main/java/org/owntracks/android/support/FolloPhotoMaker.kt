package org.owntracks.android.support

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.radzivon.bartoshyk.avif.coder.AvifEncodingOptions
import com.radzivon.bartoshyk.avif.coder.AvSpeed
import com.radzivon.bartoshyk.avif.coder.Coder
import com.radzivon.bartoshyk.avif.coder.PreciseMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber

/**
 * The small copies of a photo or video (23 Nov 2026; docs/PHOTO_BUILD_SPEC_20261123.md). Pixels upright, no metadata:
 * AVIF (avif-coder, speed FAST, quality 50 ≈ libaom speed 10 quality 60), JPEG 80 when AVIF fails on this phone. A video's thumb is a still frame; its
 * preview is H.264 + HE-AAC mono 32 kbps, 640 px long edge, ~500 kbps, first 120 s, written by Media3 Transformer with every metadata
 * entry (location, dates, XMP) dropped at the muxer.
 */
object FolloPhotoMaker {
  data class Info(val w: Int?, val h: Int?, val dur: Double?)
  data class Encoded(val bytes: ByteArray, val ct: String)

  /** Pixel size (upright) and, for a video, its length. */
  fun probe(file: File, video: Boolean): Info =
      try {
        if (video) {
          val r = MediaMetadataRetriever()
          try {
            r.setDataSource(file.path)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (rot % 180 != 0) Info(h, w, ms?.let { it / 1000.0 }) else Info(w, h, ms?.let { it / 1000.0 })
          } finally {
            r.release()
          }
        } else {
          val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeFile(file.path, o)
          val turned = when (orientation(file)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE -> true
            else -> false
          }
          val w = o.outWidth.takeIf { it > 0 }
          val h = o.outHeight.takeIf { it > 0 }
          if (turned) Info(h, w, null) else Info(w, h, null)
        }
      } catch (e: Exception) {
        Timber.w(e, "Could not read the size of a photo/video")
        Info(null, null, null)
      }

  /** The thumb: AVIF, long edge 211 px. */
  fun thumb(file: File, video: Boolean): Encoded? {
    val bmp = (if (video) frame(file) else upright(file, FolloPhotoRules.THUMB_EDGE)) ?: return null
    val scaled = scale(bmp, FolloPhotoRules.THUMB_EDGE)
    return encode(scaled, FolloPhotoRules.THUMB_MAX.toLong())
  }

  /** The photo preview: AVIF, long edge 1600 px. */
  fun preview(file: File): Encoded? {
    val bmp = upright(file, FolloPhotoRules.PREVIEW_EDGE) ?: return null
    return encode(scale(bmp, FolloPhotoRules.PREVIEW_EDGE), FolloPhotoRules.PREVIEW_PHOTO_MAX)
  }

  /** AVIF (FAST, quality 50: see FolloPhotoRules.QUALITY); JPEG 80 if the AVIF encoder fails or comes out over [max]. Neither carries any metadata. */
  fun encode(bmp: Bitmap, max: Long): Encoded? {
    val soft = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    try {
      val b = Coder().encodeAvif(soft, null, AvifEncodingOptions(FolloPhotoRules.QUALITY, PreciseMode.LOSSY, speed = AvSpeed.FAST))
      if (b.isNotEmpty() && b.size <= max) return Encoded(b, "image/avif")
      Timber.w("AVIF came out at ${b.size} bytes: JPEG instead")
    } catch (e: Throwable) {
      Timber.w(e, "AVIF encoding failed: JPEG instead")
    }
    var q = FolloPhotoRules.JPEG_QUALITY
    while (q >= 30) {
      val out = ByteArrayOutputStream()
      soft.compress(Bitmap.CompressFormat.JPEG, q, out)
      if (out.size() <= max) return Encoded(out.toByteArray(), "image/jpeg")
      q -= 15
    }
    return null
  }

  private fun orientation(file: File): Int =
      try {
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
      } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
      }

  /** The photo decoded upright, at least [edge] on its long side where the original is that big, software pixels. */
  @SuppressLint("NewApi")
  fun upright(file: File, edge: Int): Bitmap? =
      try {
        if (Build.VERSION.SDK_INT >= 28) {
          ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (tw, th) = FolloPhotoRules.fit(info.size.width, info.size.height, edge)
            if (tw > 0) d.setTargetSize(tw, th)
          } // ImageDecoder applies the EXIF turn itself
        } else {
          val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeFile(file.path, o)
          var sample = 1
          while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= edge) sample *= 2
          val b = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: null
          b?.let { turn(it, orientation(file)) }
        }
      } catch (e: Exception) {
        Timber.w(e, "Could not decode a photo")
        null
      }

  private fun turn(b: Bitmap, o: Int): Bitmap {
    val m = Matrix()
    when (o) {
      ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
      ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
      else -> return b
    }
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
  }

  private fun scale(b: Bitmap, edge: Int): Bitmap {
    val (w, h) = FolloPhotoRules.fit(b.width, b.height, edge)
    return if (w == b.width && h == b.height) b else Bitmap.createScaledBitmap(b, w, h, true)
  }

  /** A still of a video at 1 s (the middle when shorter), upright. */
  private fun frame(file: File): Bitmap? {
    val r = MediaMetadataRetriever()
    return try {
      r.setDataSource(file.path)
      val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      val at = if (ms in 1..1999) ms * 500 else 1_000_000L
      // getFrameAtTime turns the frame by the video's rotation itself (API 24+ behaviour of the framework retriever)
      r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime
    } catch (e: Exception) {
      Timber.w(e, "Could not take a frame of a video")
      null
    } finally {
      try { r.release() } catch (e: Exception) {}
    }
  }

  /** Mixes any channel layout (1–8 channels) down to the preview's mono. */
  @UnstableApi
  private fun toMono(): ChannelMixingAudioProcessor =
      ChannelMixingAudioProcessor().apply {
        // Media3's ready-made matrices stop at 2 → 1, so 3–8 channels get an equal share each
        for (n in 1..8) putChannelMixingMatrix(ChannelMixingMatrix(n, FolloPhotoRules.AUDIO_CHANNELS, FloatArray(n) { 1f / n }))
      }

  /** Drops every metadata entry (location, creation time, XMP…) the Transformer would copy into the preview. */
  @UnstableApi
  private class BareMuxerFactory(private val inner: Muxer.Factory) : Muxer.Factory by inner {
    override fun create(path: String): Muxer {
      val m = inner.create(path)
      // no creation / modification time either (the muxer would write "now"): 0 = 1 Jan 1904, i.e. none
      m.addMetadataEntry(androidx.media3.container.Mp4TimestampData(0L, 0L))
      return object : Muxer by m {
        override fun addMetadataEntry(metadataEntry: Metadata.Entry) {}
      }
    }
  }

  /**
   * The video preview into [out]: H.264 + HE-AAC (mono, 32 kbps; plain AAC if the phone has no HE-AAC encoder), 640 px long edge, ~500 kbps, first 120 s, no metadata. Runs the
   * Transformer on the main looper (it needs one). False when the phone could not make it.
   */
  @androidx.annotation.OptIn(UnstableApi::class)
  suspend fun videoPreview(context: Context, file: File, out: File): Boolean {
    val info = probe(file, true)
    val (tw, th) = FolloPhotoRules.fit(info.w ?: 1280, info.h ?: 720, FolloPhotoRules.VIDEO_EDGE)
    val evenW = tw - tw % 2
    val evenH = th - th % 2
    out.delete()
    return suspendCancellableCoroutine { cont ->
      Handler(Looper.getMainLooper()).post {
        try {
          val enc =
              DefaultEncoderFactory.Builder(context.applicationContext)
                  .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(FolloPhotoRules.VIDEO_BITRATE).build())
                  .setRequestedAudioEncoderSettings(
                      AudioEncoderSettings.Builder()
                          .setProfile(MediaCodecInfo.CodecProfileLevel.AACObjectHE)
                          .setBitrate(FolloPhotoRules.AUDIO_BITRATE)
                          .build())
                  .build()
          val tr =
              Transformer.Builder(context.applicationContext)
                  .setVideoMimeType(MimeTypes.VIDEO_H264)
                  .setAudioMimeType(MimeTypes.AUDIO_AAC)
                  .setEncoderFactory(enc)
                  .setMuxerFactory(BareMuxerFactory(DefaultMuxer.Factory()))
                  .addListener(
                      object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                          if (cont.isActive) cont.resume(out.length() in 1..FolloPhotoRules.PREVIEW_VIDEO_MAX)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                          Timber.w(exportException, "Video preview failed")
                          if (cont.isActive) cont.resume(false)
                        }
                      })
                  .build()
          val item =
              MediaItem.Builder()
                  .setUri(Uri.fromFile(file))
                  .setClippingConfiguration(
                      MediaItem.ClippingConfiguration.Builder().setEndPositionMs(FolloPhotoRules.VIDEO_MAX_S * 1000L).build())
                  .build()
          val edited =
              EditedMediaItem.Builder(item)
                  .setEffects(
                      Effects(
                          listOf(toMono()),
                          listOf(Presentation.createForWidthAndHeight(evenW, evenH, Presentation.LAYOUT_SCALE_TO_FIT))))
                  .build()
          tr.start(edited, out.path)
          cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { tr.cancel() } }
        } catch (e: Exception) {
          Timber.w(e, "Could not start the video preview")
          if (cont.isActive) cont.resume(false)
        }
      }
    }
  }
}
