package org.owntracks.android.ui.main

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.owntracks.android.support.FolloPhotoMaker

/**
 * Full-screen look at a photo or video the app took (volunteer feedback, 25 Nov 2026: "It would be good to be able to review the
 * photo in the app, I only get a thumbnail after taking the photo"). Photo: pinch or double-tap to zoom, drag to move.
 * Video: plays with the usual controls. A bar along the top holds Close and the item's actions (Save to my phone,
 * Delete); a caption line under it says anything the person should know (Possible remains, already saved).
 */
class FolloPhotoViewer(private val activity: AppCompatActivity) {
  /** A button in the top bar. [closes] = the viewer goes away after [run]. */
  class Action(val label: String, val closes: Boolean = false, val run: (dismiss: () -> Unit) -> Unit)

  private fun dp(v: Float) = (v * activity.resources.displayMetrics.density).toInt()

  /**
   * [file] = the camera's file (or null when it has gone); [fallback] = what to show for a photo when [file] cannot be
   * read (the preview or thumb).
   */
  @SuppressLint("ClickableViewAccessibility")
  fun show(file: File?, video: Boolean, fallback: (() -> Bitmap?)?, caption: String?, actions: List<Action>) {
    val dlg = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
    var video0: VideoView? = null
    if (video && file?.isFile == true) {
      val holder = FrameLayout(activity)
      val vv = VideoView(activity)
      holder.addView(vv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
      val mc = MediaController(activity)
      mc.setAnchorView(holder)
      vv.setMediaController(mc)
      vv.setVideoURI(Uri.fromFile(file))
      vv.setOnPreparedListener { mp ->
        mp.isLooping = false
        vv.start()
        mc.show(0)
      }
      vv.setOnCompletionListener { mc.show(0) }
      video0 = vv
      root.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    } else {
      val zv = ZoomImageView(activity)
      root.addView(zv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
      activity.lifecycleScope.launch {
        val b = withContext(Dispatchers.IO) {
          (if (!video && file?.isFile == true) FolloPhotoMaker.upright(file, 2560) else null) ?: fallback?.invoke()
        }
        if (b != null) zv.setImageBitmap(b)
        else if (!video) zv.contentDescription = "This photo can't be shown."
      }
    }
    // the top bar: Close on the left, the actions on the right; a caption under it
    val top = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(0x99000000.toInt())
    }
    val bar = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
    }
    fun button(label: String, onClick: () -> Unit) =
        MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
          text = label
          setTextColor(Color.WHITE)
          isAllCaps = false
          textSize = 16f
          setOnClickListener { onClick() }
        }
    bar.addView(button("Close") { dlg.dismiss() })
    bar.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
    for (a in actions) {
      bar.addView(button(a.label) {
        a.run { dlg.dismiss() }
        if (a.closes) dlg.dismiss()
      })
    }
    top.addView(bar)
    if (!caption.isNullOrBlank()) {
      top.addView(TextView(activity).apply {
        text = caption
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(dp(16f), 0, dp(16f), dp(10f))
      })
    }
    root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    // keep the bar clear of the status bar / notch
    ViewCompat.setOnApplyWindowInsetsListener(top) { v, insets ->
      val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      v.setPadding(i.left, i.top, i.right, 0)
      insets
    }
    dlg.setContentView(root)
    dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    dlg.setOnDismissListener { video0?.stopPlayback() }
    dlg.show()
  }

  /** An ImageView that fits the picture, then zooms (pinch, double-tap) and pans within it. */
  private class ZoomImageView(context: Context) : ImageView(context) {
    private val m = Matrix()
    private var base = 1f
    private var scale = 1f
    private val scaler =
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
          override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY)
            return true
          }
        })
    private val gestures =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
          override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale <= 1.01f) return false
            m.postTranslate(-dx, -dy)
            clamp()
            return true
          }

          override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1.01f) fit() else zoomBy(2.5f, e.x, e.y)
            return true
          }
        })

    init {
      scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
      super.setImageBitmap(bm)
      post { fit() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
      super.onSizeChanged(w, h, oldw, oldh)
      fit()
    }

    private fun fit() {
      val d = drawable ?: return
      if (width == 0 || height == 0 || d.intrinsicWidth <= 0) return
      base = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
      scale = 1f
      m.reset()
      m.postScale(base, base)
      m.postTranslate((width - d.intrinsicWidth * base) / 2f, (height - d.intrinsicHeight * base) / 2f)
      imageMatrix = m
    }

    private fun zoomBy(f: Float, x: Float, y: Float) {
      val next = (scale * f).coerceIn(1f, 8f)
      val k = next / scale
      scale = next
      m.postScale(k, k, x, y)
      clamp()
    }

    /** No black gaps inside the screen once the picture is bigger than it; centred while smaller. */
    private fun clamp() {
      val d = drawable ?: return
      val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
      m.mapRect(r)
      var dx = 0f
      var dy = 0f
      if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
      else if (r.left > 0) dx = -r.left
      else if (r.right < width) dx = width - r.right
      if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
      else if (r.top > 0) dy = -r.top
      else if (r.bottom < height) dy = height - r.bottom
      m.postTranslate(dx, dy)
      imageMatrix = m
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
      scaler.onTouchEvent(event)
      gestures.onTouchEvent(event)
      return true
    }
  }
}
