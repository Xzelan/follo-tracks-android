package org.owntracks.android.preferences

import kotlin.reflect.KProperty
import org.owntracks.android.preferences.types.ConnectionMode
import org.owntracks.android.preferences.types.ReverseGeocodeProvider

class DefaultsProviderImpl : DefaultsProvider {
  @Suppress("UNCHECKED_CAST", "IMPLICIT_CAST_TO_ANY")
  override fun <T> getDefaultValue(preferences: Preferences, property: KProperty<*>): T {
    return when (property) {
      Preferences::mapTileUrl -> FOLLO_DEFAULT_TILE_URL
      Preferences::mapTileMaxZoom -> FOLLO_DEFAULT_TILE_MAX_ZOOM
      Preferences::displayName -> ""
      // A phone that is not set up yet must not try an MQTT broker; FOLLO is HTTP only.
      Preferences::mode -> ConnectionMode.HTTP
      Preferences::notificationEvents -> false
      // The notification title is the banner wording, never the coordinates/address.
      Preferences::notificationLocation -> false
      Preferences::reverseGeocodeProvider -> ReverseGeocodeProvider.None
      else -> super.getDefaultValue<T>(preferences, property)
    }
        as T
  }
}

// Esri World Imagery (the FOLLO web map's "Newest" layer); z/y/x order. Only until the first server reply, which
// names the state government's aerial photography where the phone is (24 Oct 2026) and Esri only where there is none.
const val FOLLO_DEFAULT_TILE_URL =
    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

// Esri's own imagery stops at 18–19 in Australia; deeper tiles are its "Map data not yet available" picture.
const val FOLLO_DEFAULT_TILE_MAX_ZOOM = 19
