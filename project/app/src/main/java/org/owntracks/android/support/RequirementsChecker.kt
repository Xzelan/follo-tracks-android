package org.owntracks.android.support

interface RequirementsChecker {
  fun hasLocationPermissions(): Boolean

  fun hasBackgroundLocationPermission(): Boolean

  /** FOLLO: fine (precise) location, not just approximate (review 28 Oct 2026, B1). */
  fun hasPreciseLocationPermission(): Boolean

  fun isLocationServiceEnabled(): Boolean

  fun isPlayServicesCheckPassed(): Boolean

  fun hasNotificationPermissions(): Boolean
}
