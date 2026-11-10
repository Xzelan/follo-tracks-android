package org.owntracks.android.ui.main

import android.content.Context
import androidx.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.DatabaseFileArchive
import org.osmdroid.tileprovider.modules.SqlTileWriter

/**
 * osmdroid's settings for the app's one tile cache, set once per process BEFORE anything opens the cache: the map
 * (MainActivity, before its layout is inflated — the MapView opens the cache as it is created) and the offline map
 * (FolloOfflineRepo, which can run with no screen) must open the same database, and osmdroid fixes its path at the
 * first use.
 *
 * Where: the folder osmdroid chose at the first launch and keeps in the app's preferences (`osmdroid.cachePath`,
 * normally files/osmdroid/tiles). Until 0.1.33 the screen named a no-backup folder instead, but only after the MapView
 * had already opened the cache in osmdroid's own folder, so that is where every phone's tiles are (found 10 Nov 2026
 * on the emulator: no_backup/osmdroid/tiles empty, the database in files/osmdroid/tiles). Cloud backup leaves the
 * folder out (backup rules: no files).
 *
 * Room for the offline map (10 Nov 2026): osmdroid trims its cache from 600 MB to 500 MB, earliest expiry first. An
 * offline set is up to ~20,000 tiles (~220 MB) on top of what the map itself caches, so the limits are raised to
 * [MAX_BYTES] / [TRIM_BYTES] (less when the phone has not that much free), and saved tiles carry a long expiry so the
 * tiles the map merely passed over go first.
 */
object FolloTileCache {
  private const val MAX_BYTES = 1024L * 1024 * 1024
  private const val TRIM_BYTES = 900L * 1024 * 1024
  private var done = false

  @Synchronized
  fun configure(context: Context) {
    if (done) return
    val app = context.applicationContext
    Configuration.getInstance().apply {
      load(app, PreferenceManager.getDefaultSharedPreferences(app))
      userAgentValue = app.packageName
      val dir = getOsmdroidTileCache(app).also { it.mkdirs() }
      val room = dir.freeSpace + dir.resolve(SqlTileWriter.DATABASE_FILENAME).length()
      tileFileSystemCacheMaxBytes = minOf(MAX_BYTES, (room * 0.95).toLong())
      tileFileSystemCacheTrimBytes = minOf(TRIM_BYTES, (room * 0.90).toLong())
    }
    done = true
  }
}

/**
 * The tile cache's database, with the two bulk questions the offline map asks (osmdroid's own API answers one tile at
 * a time; 20,000 single queries at every app start would be slow). osmdroid keeps ONE static database for every
 * [SqlTileWriter], so this reads and writes the very rows the map draws from. Call [FolloTileCache.configure] first.
 */
class FolloTileStore : SqlTileWriter() {
  /** Every cached tile of [provider] (the tile source's name): osmdroid's database key → expiry in epoch ms (0 = none). */
  fun expiries(provider: String): HashMap<Long, Long> {
    val out = HashMap<Long, Long>()
    val db = db ?: return out
    db.rawQuery(
            "SELECT ${DatabaseFileArchive.COLUMN_KEY}, ${SqlTileWriter.COLUMN_EXPIRES} FROM ${DatabaseFileArchive.TABLE} WHERE ${DatabaseFileArchive.COLUMN_PROVIDER} = ?",
            arrayOf(provider))
        .use { cur -> while (cur.moveToNext()) out[cur.getLong(0)] = if (cur.isNull(1)) 0L else cur.getLong(1) }
    return out
  }

  /** Gives the tiles with these database keys the expiry [until], so the cache's trimming leaves them for last. */
  fun keepUntil(provider: String, keys: List<Long>, until: Long) {
    val db = db ?: return
    for (chunk in keys.chunked(400)) {
      db.execSQL(
          "UPDATE ${DatabaseFileArchive.TABLE} SET ${SqlTileWriter.COLUMN_EXPIRES} = $until WHERE ${DatabaseFileArchive.COLUMN_PROVIDER} = ? AND ${DatabaseFileArchive.COLUMN_KEY} IN (${chunk.joinToString(",")})",
          arrayOf(provider))
    }
  }
}
