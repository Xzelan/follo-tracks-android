package org.owntracks.android.data.repos

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.owntracks.android.di.ApplicationScope
import org.owntracks.android.di.CoroutineScopes
import org.owntracks.android.net.http.HttpMessageProcessorEndpoint
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.ui.main.FolloDraw
import timber.log.Timber

/**
 * The drawings base camp put on this phone's map (Alex, 12 Nov 2026; docs/DRAW_20261112.md §1–2). Every status element
 * names the version of THIS phone's set (`drawv`, "0" = none); when it differs from the version the phone holds, the
 * phone asks GET /draw with the same credentials it posts positions with, keeps the body in a file and the map
 * redraws. A change to "0" is fetched too: the answer is the empty set, and the server then knows the phone dropped
 * its drawings. Its own request, off the position queue, at most once every 30 s (a change named inside those 30 s is
 * asked for when they are up); a failed one is simply asked again by a later status.
 *
 * The file lives where backups and phone-to-phone transfer do not reach (noBackupFilesDir), and is deleted when the
 * phone is set up again or the server stops accepting it (401): a drawing is for one person's phone. Nothing about
 * drawings goes into any post.
 */
@Singleton
class FolloDrawRepo
@Inject
constructor(
    @ApplicationContext context: Context,
    private val preferences: Preferences,
    private val folloStatusRepo: FolloStatusRepo,
    @ApplicationScope private val scope: CoroutineScope,
    @CoroutineScopes.IoDispatcher private val io: CoroutineDispatcher,
) {
  /** The drawings held; null = none (nothing fetched yet, or cleared). */
  val draw = MutableStateFlow<FolloDraw?>(null)

  private val file = File(context.noBackupFilesDir, FILE)
  private var lastAskAt = 0L
  private val client by lazy {
    OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
  }

  init {
    scope.launch(io) {
      load()
      folloStatusRepo.status.collect { s -> s?.message?.drawv?.let(::follow) }
    }
    // The server no longer takes this phone's password (401 to a post; not 403): what was drawn for it goes
    scope.launch(io) { folloStatusRepo.rejectedCode.collect { if (it == 401) clear() } }
  }

  /** Version of the set the phone holds; "0" when it holds none. */
  fun heldVersion(): String = draw.value?.v ?: FolloDraw.NONE

  /** Ask again now if the phone still holds another version than the server named (the app came forward). */
  fun refresh() {
    scope.launch(io) { folloStatusRepo.status.value?.message?.drawv?.let(::follow) }
  }

  /** Forget every drawing: a new setup (the phone may now be someone else's), or the server refused this phone. */
  @Synchronized
  fun clear() {
    try {
      file.delete()
      File(file.parentFile, "$FILE.tmp").delete()
    } catch (e: Exception) {
      Timber.w(e, "Could not delete the saved drawings")
    }
    lastAskAt = 0L // the next status may ask at once (a new setup's first reply)
    if (draw.value != null) draw.value = null
  }

  private fun load() {
    try {
      if (file.exists()) FolloDraw.parse(file.readText())?.takeIf { it.v != FolloDraw.NONE }?.let { draw.value = it }
    } catch (e: Exception) {
      Timber.w(e, "Could not read the saved drawings")
    }
  }

  /** The server holds [version] for this phone: fetch when the phone holds another. On io, one at a time. */
  @Synchronized
  private fun follow(version: String) {
    if (version == heldVersion()) return
    val now = System.currentTimeMillis()
    if (now - lastAskAt < ASK_EVERY_MS) {
      // too soon after the last ask: once more when the 30 s are up (a phone standing by gets no later status to
      // remind it, and a removed drawing must not stay on its map until the app is next opened)
      askLater(ASK_EVERY_MS - (now - lastAskAt))
      return
    }
    lastAskAt = now
    val url = preferences.url.toHttpUrlOrNull()?.takeIf { it.isHttps }?.resolve("/draw") ?: return
    val user = url.username.ifEmpty { preferences.username }
    val pass = url.password.ifEmpty { preferences.password }
    if (user.isEmpty() || pass.isEmpty()) return
    try {
      val request =
          Request.Builder()
              .url(url.newBuilder().username("").password("").build())
              .header(HttpMessageProcessorEndpoint.HEADER_USERAGENT, HttpMessageProcessorEndpoint.USERAGENT)
              .header(HttpMessageProcessorEndpoint.HEADER_AUTHORIZATION, Credentials.basic(user, pass))
              .build()
      client.newCall(request).execute().use { response ->
        if (response.code == 401) {
          Timber.w("Drawings: HTTP 401, the server does not accept this phone: drawings dropped")
          clear()
          lastAskAt = now
          return
        }
        if (!response.isSuccessful) {
          Timber.w("Drawings: HTTP ${response.code}")
          return
        }
        val body = response.body.string()
        val parsed = FolloDraw.parse(body)
        if (parsed == null) {
          Timber.w("Drawings: unreadable reply")
          return
        }
        if (parsed.v == FolloDraw.NONE) {
          // the empty set: nothing is kept
          file.delete()
          draw.value = null
          Timber.d("Drawings: none")
          return
        }
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
          file.delete()
          tmp.renameTo(file)
        }
        draw.value = parsed
        Timber.d("Drawings ${parsed.v}: ${parsed.items.size} items") // never their content
      }
    } catch (e: Exception) {
      Timber.w(e, "Could not fetch the drawings")
    }
  }

  private var later: Job? = null

  private fun askLater(ms: Long) {
    if (later?.isActive == true) return
    later =
        scope.launch(io) {
          delay(ms + 100)
          folloStatusRepo.status.value?.message?.drawv?.let(::follow)
        }
  }

  companion object {
    private const val FILE = "follo_draw.json"
    private const val ASK_EVERY_MS = 30_000L
  }
}
