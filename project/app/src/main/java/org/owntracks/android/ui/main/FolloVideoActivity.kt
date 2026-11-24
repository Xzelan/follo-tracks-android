package org.owntracks.android.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import org.owntracks.android.support.FolloVideoRules
import timber.log.Timber

/**
 * The app's own video screen (24 Nov 2026). Alex: the ORIGINAL must be 1080p H.264 so every basecamp screen can play
 * it — the system camera app can't be told that (it follows its own settings: 4K, HEVC, HDR…). CameraX records the
 * back camera straight into the file FolloPhotoUi gave us ([EXTRA_FILE], cache/follo_capture, never the gallery);
 * that file is the original, never re-encoded (its SHA-256 is taken on Keep, as for photos).
 *
 * Kept minimal on purpose: preview, one record / stop button, the time, Cancel. Portrait screen; the phone's real
 * orientation goes into the file's rotation tag, so a video held sideways plays sideways-up.
 *
 * Results: RESULT_OK = a usable video is in the file; RESULT_CANCELED = nothing (file deleted);
 * [RESULT_FALLBACK] = CameraX could not run here (no back camera, bind failed, encoder refused) ⇒ FolloPhotoUi falls
 * back to the old system-camera intent, which records whatever that phone's camera app makes.
 */
class FolloVideoActivity : AppCompatActivity() {
  private lateinit var out: File
  private lateinit var preview: PreviewView
  private lateinit var timer: TextView
  private lateinit var record: FrameLayout
  private lateinit var dot: View
  private lateinit var cancel: Button
  private lateinit var note: TextView
  private var capture: VideoCapture<Recorder>? = null
  private var recording: Recording? = null
  private var discard = false
  private var finished = false
  private var withSound = false
  private var orientation: OrientationEventListener? = null

  private val askPermissions =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { got ->
        if (got[Manifest.permission.CAMERA] == true || granted(Manifest.permission.CAMERA)) start() else cameraRefused()
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    out = File(intent.getStringExtra(EXTRA_FILE) ?: run { finishWith(RESULT_CANCELED); return })
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    buildViews()
    onBackPressedDispatcher.addCallback(
        this,
        object : OnBackPressedCallback(true) {
          override fun handleOnBackPressed() {
            // Back while recording keeps the video (stop), so a stray swipe never loses what was filmed
            if (recording != null) recording?.stop() else finishWith(RESULT_CANCELED)
          }
        })
    if (granted(Manifest.permission.CAMERA) && granted(Manifest.permission.RECORD_AUDIO)) {
      start()
    } else {
      MaterialAlertDialogBuilder(this)
          .setTitle("Camera and microphone")
          .setMessage(
              "FOLLO Tracks uses the camera to record a video you choose to send basecamp, and records sound with it.\n\n" +
                  "Nothing goes to your gallery.")
          .setCancelable(false)
          .setPositiveButton("Continue") { _, _ ->
            askPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
          }
          .setNegativeButton("Cancel") { _, _ -> finishWith(RESULT_CANCELED) }
          .show()
    }
  }

  private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

  private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

  private fun buildViews() {
    val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    preview = PreviewView(this).apply {
      // FIT so what is on the screen is what is recorded (16:9), nothing cropped away
      scaleType = PreviewView.ScaleType.FIT_CENTER
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    timer = TextView(this).apply {
      setTextColor(Color.WHITE)
      textSize = 20f
      text = FolloVideoRules.elapsed(0)
      setPadding(dp(14f), dp(4f), dp(14f), dp(4f))
      background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(0x99000000.toInt()) }
    }
    root.addView(timer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    note = TextView(this).apply {
      setTextColor(Color.WHITE)
      textSize = 14f
      gravity = Gravity.CENTER
      visibility = View.GONE
      setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
      background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(0x99000000.toInt()) }
    }
    root.addView(note, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    val bar = FrameLayout(this)
    record = FrameLayout(this).apply {
      contentDescription = "Record"
      background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(4f), Color.WHITE); setColor(Color.TRANSPARENT) }
      isEnabled = false
      alpha = 0.5f
      setOnClickListener { toggle() }
    }
    dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE53935.toInt()) } }
    record.addView(dot, FrameLayout.LayoutParams(dp(60f), dp(60f), Gravity.CENTER))
    bar.addView(record, FrameLayout.LayoutParams(dp(78f), dp(78f), Gravity.CENTER))
    cancel = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
      text = "Cancel"
      setTextColor(Color.WHITE)
      textSize = 17f
      isAllCaps = false
      setOnClickListener { cancelTapped() }
    }
    bar.addView(cancel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START))
    root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120f), Gravity.BOTTOM))
    setContentView(root)
    // edge to edge (targetSdk 36): keep the time below the status bar and the buttons above the gesture bar
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
      val s = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      (timer.layoutParams as FrameLayout.LayoutParams).topMargin = s.top + dp(12f)
      (note.layoutParams as FrameLayout.LayoutParams).topMargin = s.top + dp(56f)
      (bar.layoutParams as FrameLayout.LayoutParams).bottomMargin = s.bottom
      bar.setPadding(s.left + dp(12f), 0, s.right + dp(12f), 0)
      timer.requestLayout()
      insets
    }
  }

  private fun cameraRefused() {
    MaterialAlertDialogBuilder(this)
        .setTitle("Camera not allowed")
        .setMessage("FOLLO Tracks can't record a video without the camera.\n\nTo allow it: Settings ▸ Permissions ▸ Camera ▸ Allow while using the app.")
        .setCancelable(false)
        .setPositiveButton("Settings") { _, _ ->
          try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
          } catch (e: Exception) {
            Timber.w(e, "No app settings screen")
          }
          finishWith(RESULT_CANCELED)
        }
        .setNegativeButton("Cancel") { _, _ -> finishWith(RESULT_CANCELED) }
        .show()
  }

  /** Bind the back camera: preview + 1080p H.264 SDR recorder. Any failure ⇒ RESULT_FALLBACK (system camera). */
  private fun start() {
    withSound = granted(Manifest.permission.RECORD_AUDIO)
    if (!withSound) showNote("No sound: the microphone is not allowed.")
    val future =
        try {
          ProcessCameraProvider.getInstance(this)
        } catch (e: Exception) {
          Timber.w(e, "CameraX unavailable")
          finishWith(RESULT_FALLBACK)
          return
        }
    future.addListener(
        {
          try {
            val provider = future.get()
            val recorder =
                Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
                    .setVideoMimeType(FolloVideoRules.VIDEO_MIME)
                    // AAC, not the camcorder profile's choice (the emulator's is AMR-NB 8 kHz, telephone sound)
                    .setAudioMimeType(FolloVideoRules.AUDIO_MIME)
                    .setAspectRatio(AspectRatio.RATIO_16_9)
                    .build()
            val vc = VideoCapture.Builder(recorder).setDynamicRange(DynamicRange.SDR).build()
            // the preview 16:9 like the recording, so the screen shows exactly what is filmed
            val pv =
                Preview.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build())
                    .build().also { it.surfaceProvider = preview.surfaceProvider }
            provider.unbindAll()
            val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, vc)
            Timber.i(
                "Video screen: SDR qualities %s, mime %s / %s",
                Recorder.getVideoCapabilities(camera.cameraInfo).getSupportedQualities(DynamicRange.SDR),
                recorder.videoMimeType,
                recorder.audioMimeType)
            capture = vc
            record.isEnabled = true
            record.alpha = 1f
            orientation =
                object : OrientationEventListener(this) {
                      override fun onOrientationChanged(degrees: Int) {
                        // only between recordings: CameraX writes the rotation tag when a recording starts
                        if (recording == null) FolloVideoRules.surfaceRotation(degrees)?.let { vc.targetRotation = it }
                      }
                    }
                    .also { it.enable() }
          } catch (e: Exception) {
            Timber.w(e, "Could not start the video camera; falling back to the system camera")
            finishWith(RESULT_FALLBACK)
          }
        },
        ContextCompat.getMainExecutor(this))
  }

  private fun showNote(s: String) {
    note.text = s
    note.visibility = View.VISIBLE
  }

  private fun toggle() {
    val r = recording
    if (r != null) {
      record.isEnabled = false
      r.stop()
    } else {
      begin()
    }
  }

  @SuppressLint("MissingPermission") // withAudioEnabled only when RECORD_AUDIO is granted (withSound)
  private fun begin() {
    val vc = capture ?: return
    out.delete()
    val opts = FileOutputOptions.Builder(out).setFileSizeLimit(FolloVideoRules.MAX_BYTES).build()
    val pending = vc.output.prepareRecording(this, opts)
    if (withSound && granted(Manifest.permission.RECORD_AUDIO)) pending.withAudioEnabled()
    discard = false
    recording = pending.start(ContextCompat.getMainExecutor(this)) { e -> onEvent(e) }
    (dot.layoutParams as FrameLayout.LayoutParams).apply { width = dp(30f); height = dp(30f) }
    (dot.background as GradientDrawable).apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(5f).toFloat() }
    dot.requestLayout()
    record.contentDescription = "Stop"
  }

  private fun onEvent(e: VideoRecordEvent) {
    when (e) {
      is VideoRecordEvent.Status -> timer.text = "● " + FolloVideoRules.elapsed(e.recordingStats.recordedDurationNanos)
      is VideoRecordEvent.Finalize -> {
        recording = null
        val bytes = if (out.exists()) out.length() else 0L
        Timber.i("Video finished: error %d, %d bytes", e.error, bytes)
        when {
          discard -> {
            out.delete()
            finishWith(RESULT_CANCELED)
          }
          FolloVideoRules.keepAfter(e.error, bytes) -> finishWith(RESULT_OK)
          e.error == FolloVideoRules.ERROR_NO_VALID_DATA -> {
            // stopped before the first frame was written: let them try again
            out.delete()
            resetButton()
            Toast.makeText(this, "Too short: nothing was recorded. Tap the red button to record.", Toast.LENGTH_LONG).show()
          }
          else -> {
            Timber.w(e.cause, "Video recording failed (%d); falling back to the system camera", e.error)
            out.delete()
            finishWith(RESULT_FALLBACK)
          }
        }
      }
      else -> {}
    }
  }

  private fun resetButton() {
    (dot.layoutParams as FrameLayout.LayoutParams).apply { width = dp(60f); height = dp(60f) }
    (dot.background as GradientDrawable).shape = GradientDrawable.OVAL
    dot.requestLayout()
    record.isEnabled = true
    record.contentDescription = "Record"
    timer.text = FolloVideoRules.elapsed(0)
  }

  private fun cancelTapped() {
    val r = recording ?: return finishWith(RESULT_CANCELED)
    MaterialAlertDialogBuilder(this)
        .setTitle("Throw this video away?")
        .setMessage("What you have recorded so far is deleted.")
        .setPositiveButton("Throw away") { _, _ ->
          discard = true
          r.stop()
        }
        .setNegativeButton("Keep recording", null)
        .show()
  }

  private fun finishWith(code: Int) {
    if (finished) return
    finished = true
    if (code != RESULT_OK && code != RESULT_FALLBACK) out.delete()
    setResult(code)
    finish()
  }

  override fun onDestroy() {
    orientation?.disable()
    super.onDestroy()
  }

  companion object {
    const val EXTRA_FILE = "file"
    const val RESULT_FALLBACK = RESULT_FIRST_USER
  }
}
