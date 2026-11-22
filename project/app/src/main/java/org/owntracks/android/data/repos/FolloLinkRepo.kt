package org.owntracks.android.data.repos

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.owntracks.android.di.ApplicationScope
import org.owntracks.android.ui.main.FolloMenu

/**
 * The two-way symbol's facts (Alex, 19 Nov 2026; SIMPLE_MODE_SPEC §3): how long the oldest thing the app has queued
 * has gone unanswered, and whether the server has ever answered this phone. Watches the send queue the app already
 * has; it sends nothing and asks for nothing.
 */
@Singleton
class FolloLinkRepo
@Inject
constructor(
    endpointStateRepo: EndpointStateRepo,
    private val folloStatusRepo: FolloStatusRepo,
    @ApplicationScope scope: CoroutineScope,
) {
  /** When the send queue was last seen to go from empty to holding something (epoch ms); null while it is empty. */
  @Volatile
  var queuedSince: Long? = null
    private set

  /** Debug builds only: the colour to show whatever the facts are (screenshots). */
  @Volatile var demo: FolloMenu.Link? = null

  init {
    scope.launch {
      endpointStateRepo.endpointQueueLength.collect { n ->
        if (n > 0) {
          if (queuedSince == null) queuedSince = System.currentTimeMillis()
        } else queuedSince = null
      }
    }
  }

  /** The server's last answer to this phone: its last status element or the last position it accepted (0 = never). */
  fun answeredAt(): Long = maxOf(folloStatusRepo.status.value?.receivedAt ?: 0L, folloStatusRepo.lastSentAt.value)

  fun link(now: Long = System.currentTimeMillis()): FolloMenu.Link =
      demo ?: FolloMenu.link(FolloMenu.waitingMs(queuedSince, answeredAt(), now), answeredAt() > 0L)
}
