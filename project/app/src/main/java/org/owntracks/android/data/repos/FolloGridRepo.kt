package org.owntracks.android.data.repos

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
import org.owntracks.android.ui.main.FolloGrid
import timber.log.Timber

/**
 * The search grids the map draws (Alex, 10 Nov 2026: a fallback if QField fails; docs/OFFLINE_GRIDS_20261110.md §1–2).
 * Every status element names the version the server holds (`gridv`); when it differs from the version of the file the
 * phone holds, the phone asks GET /grid with the same credentials it posts positions with, keeps the body in a file
 * and the map redraws. Its own request, off the position queue, at most once a minute; a failed one is simply asked
 * again by a later status. The file survives restarts, so the grids draw with no signal.
 */
@Singleton
class FolloGridRepo
@Inject
constructor(
    @ApplicationContext context: Context,
    private val preferences: Preferences,
    private val folloStatusRepo: FolloStatusRepo,
    @ApplicationScope private val scope: CoroutineScope,
    @CoroutineScopes.IoDispatcher private val io: CoroutineDispatcher,
) {
  /** The grids held; null until the first file is read or fetched. */
  val grid = MutableStateFlow<FolloGrid?>(null)

  private val file = File(context.filesDir, FILE)
  private var lastAskAt = 0L
  private val client by lazy {
    OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
  }

  init {
    scope.launch(io) {
      load()
      folloStatusRepo.status.collect { s -> s?.message?.gridv?.let(::follow) }
    }
  }

  /** Version of the grids the phone holds, acknowledged in every post (`fd.gv`); null = none yet. */
  fun heldVersion(): String? = grid.value?.v

  /** Ask again now if the phone still holds another version than the server named (the app came forward). */
  fun refresh() {
    scope.launch(io) { folloStatusRepo.status.value?.message?.gridv?.let(::follow) }
  }

  private fun load() {
    try {
      if (file.exists()) FolloGrid.parse(file.readText())?.let { grid.value = it }
    } catch (e: Exception) {
      Timber.w(e, "Could not read the saved search grids")
    }
  }

  /** The server holds [version]: fetch when the phone holds another. On io, one at a time. */
  @Synchronized
  private fun follow(version: String) {
    if (version == heldVersion()) return
    val now = System.currentTimeMillis()
    if (now - lastAskAt < ASK_EVERY_MS) return
    lastAskAt = now
    val url = preferences.url.toHttpUrlOrNull()?.takeIf { it.isHttps }?.resolve("/grid") ?: return
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
        if (!response.isSuccessful) {
          Timber.w("Search grids: HTTP ${response.code}")
          return
        }
        val body = response.body.string()
        val parsed = FolloGrid.parse(body)
        if (parsed == null) {
          Timber.w("Search grids: unreadable reply")
          return
        }
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
          file.delete()
          tmp.renameTo(file)
        }
        grid.value = parsed
        Timber.d("Search grids ${parsed.v}: ${parsed.areas.size} areas, ${parsed.lineCount} lines")
      }
    } catch (e: Exception) {
      Timber.w(e, "Could not fetch the search grids")
    }
  }

  companion object {
    private const val FILE = "follo_grid.json"
    private const val ASK_EVERY_MS = 60_000L
  }
}
