package org.owntracks.android.preferences

import kotlin.reflect.KProperty
import org.owntracks.android.preferences.types.ConnectionMode
import org.owntracks.android.preferences.types.ReverseGeocodeProvider

class DefaultsProviderImpl : DefaultsProvider {
  @Suppress("UNCHECKED_CAST", "IMPLICIT_CAST_TO_ANY")
  override fun <T> getDefaultValue(preferences: Preferences, property: KProperty<*>): T {
    return when (property) {
      Preferences::mapTileUrl -> FOLLO_DEFAULT_TILE_URL
      Preferences::displayName -> ""
      // A phone that is not set up yet must not try an MQTT broker; FOLLO is HTTP only.
      Preferences::mode -> ConnectionMode.HTTP
      Preferences::notificationEvents -> false
      Preferences::reverseGeocodeProvider -> ReverseGeocodeProvider.None
      else -> super.getDefaultValue<T>(preferences, property)
    }
        as T
  }
}

// Esri World Imagery, the same source as the FOLLO web map's "Highest res" layer; z/y/x order.
const val FOLLO_DEFAULT_TILE_URL =
    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
