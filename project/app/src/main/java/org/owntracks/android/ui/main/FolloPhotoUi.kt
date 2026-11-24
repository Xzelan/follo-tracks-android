package org.owntracks.android.ui.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.radzivon.bartoshyk.avif.coder.Coder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.owntracks.android.data.repos.FolloPhotoRepo
import org.owntracks.android.support.FolloGallery
import org.owntracks.android.support.FolloPhoto
import org.owntracks.android.support.FolloPhotoMaker
import org.owntracks.android.support.FolloPhotoRules
import timber.log.Timber

/**
 * The 📷 button's screens (23 Nov 2026; FOLLO_tracks/docs/PHOTO_BUILD_SPEC_20261123.md): Photo · Video · Cancel ⇒ the
 * system camera (photos) or the app's own video screen (FolloVideoActivity, 24 Nov 2026: 1080p H.264 originals) writes
 * straight into the app's own storage (never the gallery) ⇒ one sheet (note, ⚠ Possible remains,
 * Keep · Delete) ⇒ FolloPhotoRepo. And the "Photos & video" list under "…". Made as a field of MainActivity, so the
 * camera launchers are registered before the activity starts; the shot being taken survives the activity being
 * recreated (its path is kept in prefs).
 */
class FolloPhotoUi(
    private val activity: AppCompatActivity,
    private val repo: () -> FolloPhotoRepo,
    private val here: () -> Location?,
    private val heading: () -> Double?,
) {
  private val prefs by lazy { activity.getSharedPreferences("follo_photo_ui", Context.MODE_PRIVATE) }

  private val takePhoto = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> shotDone(ok, video = false) }
  /** Only when the app's own video screen can't run on this phone (FolloVideoActivity.RESULT_FALLBACK). */
  private val takeVideo = activity.registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok -> shotDone(ok, video = true) }
  private val recordVideo =
      activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == FolloVideoActivity.RESULT_FALLBACK) systemVideo() else shotDone(r.resultCode == Activity.RESULT_OK, video = true)
      }
  /**
   * The app now declares CAMERA (for its own video screen), and Android then refuses the system camera's photo intent
   * unless CAMERA is granted ⇒ photos ask for it first too.
   */
  private val askCamera =
      activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) open(video = false) else cameraRefused() }
  /** The item waiting for the storage permission (Android 7–9 only) before "Save to my phone" can run. */
  private var saveWaiting: FolloPhoto? = null
  private val viewer by lazy { FolloPhotoViewer(activity) }

  /** Redraws the open Photos & video list (its rows say "Saved to your phone"); null while it is closed. */
  private var listRefresh: (() -> Unit)? = null
  private val askStorage =
      activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = saveWaiting
        saveWaiting = null
        if (p == null) return@registerForActivityResult
        if (ok) saveToPhone(p)
        else Toast.makeText(activity, "Not saved: FOLLO Tracks needs permission to save to your phone.", Toast.LENGTH_LONG).show()
      }

  /** The 📷 button was tapped. */
  fun capture() {
    MaterialAlertDialogBuilder(activity)
        .setTitle("Send basecamp a photo or video")
        .setItems(arrayOf("Photo", "Video")) { _, which -> open(video = which == 1) }
        .setNegativeButton("Cancel", null)
        .show()
  }

  private fun open(video: Boolean) {
    if (!video && ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      MaterialAlertDialogBuilder(activity)
          .setTitle("Camera")
          .setMessage("FOLLO Tracks uses the camera to take a photo you choose to send basecamp. Nothing goes to your gallery.")
          .setPositiveButton("Continue") { _, _ -> askCamera.launch(Manifest.permission.CAMERA) }
          .setNegativeButton("Cancel", null)
          .show()
      return
    }
    val f = captureFile(video)
    prefs.edit().putString(KEY_FILE, f.path).putBoolean(KEY_VIDEO, video).putLong(KEY_AT, System.currentTimeMillis()).apply()
    try {
      if (video) {
        // the app's own screen: 1080p H.264 (Alex 24 Nov 2026); it asks for camera + microphone itself
        recordVideo.launch(Intent(activity, FolloVideoActivity::class.java).putExtra(FolloVideoActivity.EXTRA_FILE, f.path))
      } else {
        takePhoto.launch(FileProvider.getUriForFile(activity, activity.packageName + ".photos", f))
      }
    } catch (e: Exception) {
      Timber.w(e, "No camera app")
      Toast.makeText(activity, "This phone has no camera app FOLLO Tracks can use.", Toast.LENGTH_LONG).show()
    }
  }

  /** The old way (system camera app, whatever it records): only when the app's own video screen can't run here. */
  private fun systemVideo() {
    val path = prefs.getString(KEY_FILE, null) ?: return
    try {
      takeVideo.launch(FileProvider.getUriForFile(activity, activity.packageName + ".photos", File(path)))
    } catch (e: Exception) {
      Timber.w(e, "No camera app")
      prefs.edit().remove(KEY_FILE).apply()
      Toast.makeText(activity, "This phone has no camera app FOLLO Tracks can use.", Toast.LENGTH_LONG).show()
    }
  }

  private fun cameraRefused() {
    MaterialAlertDialogBuilder(activity)
        .setTitle("Camera not allowed")
        .setMessage("FOLLO Tracks can't take a photo without the camera.\n\nTo allow it: Settings ▸ Permissions ▸ Camera ▸ Allow while using the app.")
        .setPositiveButton("Settings") { _, _ ->
          try {
            activity.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", activity.packageName, null)))
          } catch (e: Exception) {
            Timber.w(e, "No app settings screen")
          }
        }
        .setNegativeButton("Cancel", null)
        .show()
  }

  /** A file in cache/follo_capture the camera app may write to (shared through the FileProvider, nothing else is). */
  private fun captureFile(video: Boolean): File {
    val d = File(activity.cacheDir, "follo_capture")
    d.mkdirs()
    d.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
    return File(d, (if (video) "v_" else "p_") + System.currentTimeMillis() + if (video) ".mp4" else ".jpg")
  }

  private fun shotDone(ok: Boolean, video: Boolean) {
    val path = prefs.getString(KEY_FILE, null) ?: return
    val takenAt = prefs.getLong(KEY_AT, System.currentTimeMillis())
    prefs.edit().remove(KEY_FILE).apply()
    val f = File(path)
    if (!f.exists() || f.length() == 0L) {
      f.delete()
      if (ok) Toast.makeText(activity, "The camera saved nothing.", Toast.LENGTH_SHORT).show()
      return
    }
    // the position and heading as they are now, right after the shot (spec: the newest fix ≤ 5 min old)
    val fix = here()?.takeIf { System.currentTimeMillis() - it.time <= FolloPhotoRules.FIX_MAX_AGE_MS }
    val head = heading()
    sheet(f, video, takenAt, fix, head)
  }

  private fun dp(v: Float) = (v * activity.resources.displayMetrics.density).toInt()

  /** After the shot: the picture small, a note, ⚠ Possible remains, Keep · Delete. */
  private fun sheet(f: File, video: Boolean, takenAt: Long, fix: Location?, head: Double?) {
    val box = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(22f), dp(12f), dp(22f), 0)
    }
    val img = ImageView(activity).apply {
      adjustViewBounds = true
      maxHeight = dp(220f)
      scaleType = ImageView.ScaleType.FIT_CENTER
    }
    box.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    activity.lifecycleScope.launch {
      val b = withContext(Dispatchers.IO) { previewBitmap(f, video) }
      if (b != null) img.setImageBitmap(b)
    }
    // 25 Nov 2026 (volunteer feedback): look at it properly before sending
    img.setOnClickListener { viewer.show(f, video, { previewBitmap(f, video) }, null, emptyList()) }
    box.addView(TextView(activity).apply {
      text = if (video) "Tap the picture to play the video." else "Tap the picture to see it full size."
      textSize = 13f
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(0, dp(4f), 0, dp(4f))
    })
    val note = EditText(activity).apply {
      hint = "Note (optional)"
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
      filters = arrayOf(InputFilter.LengthFilter(FolloPhotoRules.NOTE_MAX))
      setSingleLine(true)
    }
    box.addView(note)
    val find = CheckBox(activity).apply { text = "⚠ Possible remains" }
    box.addView(find)
    box.addView(TextView(activity).apply {
      text = "Talk to your Team Leader first if you can. Only the FOLLO committee can open it at basecamp."
      setPadding(dp(32f), 0, 0, dp(8f))
    })
    box.addView(TextView(activity).apply {
      text = "Basecamp sees a small copy straight away. They can ask your phone for a bigger one."
      textSize = 13f
      setPadding(0, dp(4f), 0, dp(4f))
    })
    MaterialAlertDialogBuilder(activity)
        .setTitle(if (video) "Video" else "Photo")
        .setView(box)
        .setCancelable(false)
        .setPositiveButton("Send") { _, _ ->
          activity.lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) {
              repo().add(f, video, note.text?.toString(), find.isChecked, fix?.latitude, fix?.longitude, fix?.accuracy?.toDouble(), fix?.time, head, takenAt)
            }
            Toast.makeText(activity, if (p != null) "Kept. The small copy goes to basecamp now." else "That could not be kept.", Toast.LENGTH_SHORT).show()
          }
        }
        .setNegativeButton("Delete") { _, _ -> f.delete() }
        .show()
  }

  private fun previewBitmap(f: File, video: Boolean): Bitmap? =
      try {
        if (video) {
          val r = android.media.MediaMetadataRetriever()
          try { r.setDataSource(f.path); r.getFrameAtTime(0) } finally { r.release() }
        } else FolloPhotoMaker.upright(f, 800)
      } catch (e: Exception) {
        null
      }

  /** The thumb of an item, decoded (AVIF through avif-coder: Android before 12 cannot decode AVIF itself). */
  private fun thumbOf(p: FolloPhoto): Bitmap? =
      try {
        val t = repo().file(p, "t")?.takeIf { it.exists() }?.readBytes()
        when {
          t == null -> null
          p.tct == "image/avif" -> Coder().decode(t)
          else -> BitmapFactory.decodeByteArray(t, 0, t.size)
        }
      } catch (e: Exception) {
        null
      }

  /** "Photos & video" under "…": this phone's items, newest first, with their state in words; tap = Delete (asks). */
  fun showList() {
    val r = repo()
    val time = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
    val thumbs = HashMap<String, Bitmap?>()
    var rows: List<FolloPhoto> = emptyList()
    val adapter =
        object : BaseAdapter() {
          override fun getCount() = rows.size
          override fun getItem(i: Int) = rows[i]
          override fun getItemId(i: Int) = i.toLong()
          override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val p = rows[i]
            val row = (convert as? LinearLayout) ?: LinearLayout(activity).apply {
              orientation = LinearLayout.HORIZONTAL
              gravity = Gravity.CENTER_VERTICAL
              setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
              addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(dp(64f), dp(48f)))
              addView(TextView(activity).apply { setPadding(dp(12f), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val iv = row.getChildAt(0) as ImageView
            val tv = row.getChildAt(1) as TextView
            iv.setImageBitmap(thumbs.getOrPut(p.pid) { thumbOf(p) })
            val head = (if (p.type == "video") "🎥 " else "📷 ") + time.format(Date(p.t)) + if (p.tag == "find") "  ⚠ Possible remains" else ""
            tv.text = head + (p.note?.let { "\n“$it”" } ?: "") + "\n" + r.words(p) + if (savedPids().contains(p.pid)) "\nSaved to your phone" else ""
            return row
          }
        }
    val list = ListView(activity).apply { this.adapter = adapter }
    val empty = TextView(activity).apply {
      text = "No photos or videos on this phone."
      setPadding(dp(24f), dp(16f), dp(24f), dp(16f))
    }
    val box = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      addView(empty)
      addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    fun refresh() {
      rows = r.items.value.filter { !it.del }.sortedByDescending { it.t }
      empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
      adapter.notifyDataSetChanged()
    }
    refresh()
    var watch: Job? = null
    val dlg: AlertDialog =
        MaterialAlertDialogBuilder(activity)
            .setTitle("Photos & video")
            .setView(box)
            .setPositiveButton("Close", null)
            .setOnDismissListener {
              watch?.cancel()
              listRefresh = null
            }
            .show()
    listRefresh = { refresh() }
    watch = activity.lifecycleScope.launch { combine(r.items, r.wants, r.online) { _, _, _ -> }.collect { refresh() } }
    list.setOnItemClickListener { _, _, i, _ ->
      val p = rows.getOrNull(i) ?: return@setOnItemClickListener
      itemMenu(r, p)
    }
    dlg.toString()
  }

  /**
   * Tap on a row of the list (25 Nov 2026): the photo or video full screen (volunteer feedback), with Save to my phone · Delete ·
   * Close. Save is left out for ⚠ Possible remains (committee only) and when the camera's file is no longer on the
   * phone (it goes 7 days after FOLLO has it).
   */
  private fun itemMenu(r: FolloPhotoRepo, p: FolloPhoto) {
    val what = if (p.type == "video") "video" else "photo"
    val orig = r.file(p, "o")?.takeIf { it.isFile }
    val saved = savedPids().contains(p.pid)
    val caption =
        when {
          !FolloPhotoRules.canSaveToPhone(p) ->
              "⚠ Possible remains: this $what stays in FOLLO Tracks. Only the committee can open it."
          orig == null -> "This phone no longer has the full-size $what. FOLLO still has it."
          saved -> "A copy is already in your phone's Gallery, in the FOLLO Tracks album."
          else -> null
        }
    val actions = mutableListOf<FolloPhotoViewer.Action>()
    if (FolloPhotoRules.canSaveToPhone(p) && orig != null)
        actions += FolloPhotoViewer.Action(if (saved) "Save again" else "Save to my phone") { saveToPhone(p) }
    actions += FolloPhotoViewer.Action("Delete") { dismiss -> confirmDelete(r, p, saved, dismiss) }
    viewer.show(orig, p.type == "video", { fallbackOf(r, p) }, caption, actions)
  }

  /** What to show for a photo when the camera's file has gone: the bigger copy if the phone made one, else the thumb. */
  private fun fallbackOf(r: FolloPhotoRepo, p: FolloPhoto): Bitmap? =
      try {
        val big = r.file(p, "p")?.takeIf { it.exists() }?.readBytes()
        when {
          big != null && p.pct == "image/avif" -> Coder().decode(big)
          big != null && p.pct?.startsWith("image/") == true -> BitmapFactory.decodeByteArray(big, 0, big.size)
          else -> thumbOf(p)
        }
      } catch (e: Exception) {
        thumbOf(p)
      }

  private fun confirmDelete(r: FolloPhotoRepo, p: FolloPhoto, saved: Boolean, deleted: () -> Unit = {}) {
    MaterialAlertDialogBuilder(activity)
        .setTitle(if (p.type == "video") "Delete this video?" else "Delete this photo?")
        .setMessage(
            "It goes from this phone and from FOLLO, including basecamp's copies. This cannot be undone." +
                if (saved) "\n\nThe copy you saved to your Gallery stays." else "")
        .setPositiveButton("Delete") { _, _ ->
          r.delete(p.pid)
          deleted()
        }
        .setNegativeButton("Keep", null)
        .show()
  }

  private fun savedPids(): Set<String> = prefs.getStringSet(KEY_SAVED, emptySet()) ?: emptySet()

  /** Copies the camera's file into DCIM/FOLLO Tracks (FolloGallery), asking for storage first on Android 7–9. */
  private fun saveToPhone(p: FolloPhoto) {
    if (!FolloPhotoRules.canSaveToPhone(p)) return
    if (FolloGallery.needsPermission() &&
        ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      saveWaiting = p
      askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
      return
    }
    val src = repo().file(p, "o") ?: return
    val app = activity.applicationContext
    val what = if (p.type == "video") "Video" else "Photo"
    Toast.makeText(activity, "Saving…", Toast.LENGTH_SHORT).show()
    activity.lifecycleScope.launch {
      val err = withContext(Dispatchers.IO) { runCatching { FolloGallery.save(app, src, p) }.exceptionOrNull() }
      if (err == null) {
        prefs.edit().putStringSet(KEY_SAVED, savedPids() + p.pid).apply()
        listRefresh?.invoke()
        Toast.makeText(app, "$what saved to your Gallery (FOLLO Tracks album).", Toast.LENGTH_LONG).show()
      } else {
        Timber.w(err, "save to phone failed for %s", p.pid)
        Toast.makeText(app, "Could not save the ${what.lowercase()}: ${err.message}", Toast.LENGTH_LONG).show()
      }
    }
  }

  companion object {
    private const val KEY_SAVED = "saved_to_phone"
    private const val KEY_FILE = "file"
    private const val KEY_VIDEO = "video"
    private const val KEY_AT = "at"
  }
}
