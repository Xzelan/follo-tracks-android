package org.owntracks.android.data.repos

import android.location.Location
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class LocationRepo @Inject constructor() {
  /** The last location that was published to the network */
  val currentPublishedLocation: StateFlow<Location?>
    get() = mutableCurrentPublishedLocation

  private val mutableCurrentPublishedLocation = MutableStateFlow<Location?>(null)

  val currentLocationTime: Long
    get() = currentPublishedLocation.value?.time ?: 0

  suspend fun setCurrentPublishedLocation(location: Location) {
    mutableCurrentPublishedLocation.emit(location)
  }
}
