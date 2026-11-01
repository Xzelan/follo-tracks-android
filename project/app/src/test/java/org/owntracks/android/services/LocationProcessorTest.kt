package org.owntracks.android.services

import android.content.Context
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.owntracks.android.data.repos.FolloDiagRepo
import org.owntracks.android.data.repos.FolloStatusRepo
import org.owntracks.android.data.repos.LocationRepo
import org.owntracks.android.data.waypoints.InMemoryWaypointsRepo
import org.owntracks.android.data.waypoints.WaypointModel
import org.owntracks.android.location.geofencing.Latitude
import org.owntracks.android.location.geofencing.Longitude
import org.owntracks.android.model.messages.MessageBase
import org.owntracks.android.model.messages.MessageFollo
import org.owntracks.android.model.messages.MessageHello
import org.owntracks.android.model.messages.MessageLocation
import org.owntracks.android.net.WifiInfoProvider
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.preferences.types.MonitoringMode
import org.owntracks.android.preferences.types.StringMaxTwoAlphaNumericChars
import org.owntracks.android.support.DeviceMetricsProvider
import org.owntracks.android.test.SimpleIdlingResource

class LocationProcessorTest {
  @Test
  fun `given disabled threshold, speed is not implausible`() {
    assertFalse(isImplausibleSpeed(100000.0, 1000, 0))
  }

  @Test
  fun `given plausible slow move, speed is not implausible`() {
    assertFalse(isImplausibleSpeed(100.0, 60000, 10))
  }

  @Test
  fun `given teleport move, speed is implausible`() {
    assertTrue(isImplausibleSpeed(24140.16, 5000, 500))
  }

  @Test
  fun `given zero time delta, speed is not implausible`() {
    assertFalse(isImplausibleSpeed(1000.0, 0, 10))
  }

  @Test
  fun `given negative time delta, speed is not implausible`() {
    assertFalse(isImplausibleSpeed(1000.0, -1000, 10))
  }

  /** Regression test for https://github.com/owntracks/android/issues/2034 review follow-up. */
  @Test
  fun `RESPONSE trigger is not dropped by the implausible speed filter`() = runTest {
    val preferences =
        mock<Preferences> {
          on { maxImplausibleSpeedKmh } doReturn 50
          on { ignoreInaccurateLocations } doReturn 0
          on { discardNetworkLocationThresholdSeconds } doReturn 0
          on { fusedRegionDetection } doReturn false
          on { monitoring } doReturn MonitoringMode.Significant
          on { tid } doReturn StringMaxTwoAlphaNumericChars("AB")
        }
    val messageProcessor = mock<MessageProcessor>()
    val locationProcessor =
        LocationProcessor(
            messageProcessor,
            preferences,
            LocationRepo(),
            heldSearchAtZero(),
            mock<DeviceMetricsProvider>(),
            mock<WifiInfoProvider>(),
            this,
            Dispatchers.Unconfined,
            SimpleIdlingResource("publishResponseMessageIdlingResource", false),
            SimpleIdlingResource("mockLocationIdlingResource", false),
            false,
            mock<FolloDiagRepo>(),
            noStatus(),
        )

    val firstLocation =
        mock<Location> {
          on { time } doReturn 1_000_000L
          on { provider } doReturn "gps"
          on { accuracy } doReturn 5f
        }
    locationProcessor.onLocationChanged(firstLocation, MessageLocation.ReportType.DEFAULT)

    // ~50km in 5 seconds - far beyond the 50km/h mocked ceiling - but a RESPONSE to an explicit
    // "reportLocation" request must still be published, never silently dropped.
    val secondLocation =
        mock<Location> {
          on { time } doReturn 1_005_000L
          on { provider } doReturn "gps"
          on { accuracy } doReturn 5f
          on { distanceTo(firstLocation) } doReturn 50_000f
        }
    locationProcessor.onLocationChanged(secondLocation, MessageLocation.ReportType.RESPONSE)

    val captor = argumentCaptor<MessageBase>()
    verify(messageProcessor, times(2)).queueMessageForSending(captor.capture())
    assertEquals(
        MessageLocation.ReportType.RESPONSE,
        (captor.secondValue as MessageLocation).trigger,
    )
  }

  /** No boundary held, and a TEST search window open from 1970 to 2100, so the TEST circle counts (1 Nov 2026). */
  private fun noStatus() =
      mock<FolloStatusRepo> {
        on { status } doReturn
            MutableStateFlow<FolloStatusRepo.Status?>(
                FolloStatusRepo.Status(
                    MessageFollo().apply {
                      wins = listOf(listOf(JsonPrimitive("TEST"), JsonPrimitive(0), JsonPrimitive(4_102_444_800L)))
                    },
                    0L,
                )
            )
      }

  /**
   * FOLLO (1 Nov 2026): a fix is only posted inside a held circle or boundary. The mocked fixes here sit at 0,0, so the
   * phone holds a wake-up circle there.
   */
  private suspend fun TestScope.heldSearchAtZero(): InMemoryWaypointsRepo =
      InMemoryWaypointsRepo(this, mock<Context>(), Dispatchers.Unconfined).apply {
        insert(
            WaypointModel(
                description = "FOLLO TEST wake 1",
                geofenceLatitude = Latitude(0.0),
                geofenceLongitude = Longitude(0.0),
                geofenceRadius = 1000,
            )
        )
      }

  private fun fixAt(lat: Double, t: Long, type: String = "gps") =
      mock<Location> {
        on { latitude } doReturn lat
        on { longitude } doReturn 0.0
        on { time } doReturn t
        on { provider } doReturn type
        on { accuracy } doReturn 5f
      }

  private suspend fun TestScope.outsideProcessor(
      messageProcessor: MessageProcessor,
      preferences: Preferences,
  ) =
      LocationProcessor(
          messageProcessor,
          preferences,
          LocationRepo(),
          heldSearchAtZero(),
          mock<DeviceMetricsProvider>(),
          mock<WifiInfoProvider>(),
          this,
          Dispatchers.Unconfined,
          SimpleIdlingResource("publishResponseMessageIdlingResource", false),
          SimpleIdlingResource("mockLocationIdlingResource", false),
          false,
          mock<FolloDiagRepo>(),
          noStatus(),
      )

  private fun movingPreferences() =
      mock<Preferences> {
        on { maxImplausibleSpeedKmh } doReturn 0
        on { ignoreInaccurateLocations } doReturn 0
        on { discardNetworkLocationThresholdSeconds } doReturn 0
        on { fusedRegionDetection } doReturn false
        on { monitoring } doReturn MonitoringMode.Move
        on { tid } doReturn StringMaxTwoAlphaNumericChars("AB")
      }

  /** Alex, 1 Nov 2026: never a position from outside a search circle. 0.1° north of the circle = 11 km outside. */
  @Test
  fun `a fix outside the search is not sent, the phone stands by and says hello without it`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val preferences = movingPreferences()
    val lp = outsideProcessor(messageProcessor, preferences)

    lp.onLocationChanged(fixAt(0.1, 1_000_000L), MessageLocation.ReportType.DEFAULT)
    lp.onLocationChanged(fixAt(0.1, 1_010_000L), MessageLocation.ReportType.PING)
    lp.onLocationChanged(fixAt(0.1, 1_020_000L), MessageLocation.ReportType.USER)

    val captor = argumentCaptor<MessageBase>()
    verify(messageProcessor, times(1)).queueMessageForSending(captor.capture())
    val hello = captor.firstValue as MessageHello
    assertEquals("out", hello.src)
    verify(preferences, times(3)).monitoring = MonitoringMode.Manual
  }

  @Test
  fun `inside the circle but no search window open, the fix is not sent`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val closed =
        mock<FolloStatusRepo> {
          on { status } doReturn
              MutableStateFlow<FolloStatusRepo.Status?>(
                  FolloStatusRepo.Status(
                      MessageFollo().apply { wins = listOf(listOf(JsonPrimitive("TEST"), JsonPrimitive(0), JsonPrimitive(1))) },
                      0L,
                  )
              )
        }
    val lp =
        LocationProcessor(
            messageProcessor,
            movingPreferences(),
            LocationRepo(),
            heldSearchAtZero(),
            mock<DeviceMetricsProvider>(),
            mock<WifiInfoProvider>(),
            this,
            Dispatchers.Unconfined,
            SimpleIdlingResource("publishResponseMessageIdlingResource", false),
            SimpleIdlingResource("mockLocationIdlingResource", false),
            false,
            mock<FolloDiagRepo>(),
            closed,
        )
    lp.onLocationChanged(fixAt(0.0, 1_000_000L), MessageLocation.ReportType.DEFAULT) // dead centre of the circle

    val captor = argumentCaptor<MessageBase>()
    verify(messageProcessor, times(1)).queueMessageForSending(captor.capture())
    assertTrue(captor.firstValue is MessageHello)
  }

  @Test
  fun `back inside, fixes go again and the next time outside says hello again`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val lp = outsideProcessor(messageProcessor, movingPreferences())

    lp.onLocationChanged(fixAt(0.1, 1_000_000L), MessageLocation.ReportType.DEFAULT) // out: hello
    lp.onLocationChanged(fixAt(0.001, 1_010_000L), MessageLocation.ReportType.DEFAULT) // 111 m from the centre: sent
    lp.onLocationChanged(fixAt(0.1, 1_020_000L), MessageLocation.ReportType.DEFAULT) // out again: hello

    val captor = argumentCaptor<MessageBase>()
    verify(messageProcessor, times(3)).queueMessageForSending(captor.capture())
    assertTrue(captor.allValues[0] is MessageHello)
    assertTrue(captor.allValues[1] is MessageLocation)
    assertTrue(captor.allValues[2] is MessageHello)
  }

  private suspend fun TestScope.buildLocationProcessor(
      messageProcessor: MessageProcessor,
      discardThresholdSeconds: Int,
  ): LocationProcessor {
    val preferences =
        mock<Preferences> {
          on { maxImplausibleSpeedKmh } doReturn 0
          on { ignoreInaccurateLocations } doReturn 0
          on { discardNetworkLocationThresholdSeconds } doReturn discardThresholdSeconds
          on { fusedRegionDetection } doReturn false
          on { monitoring } doReturn MonitoringMode.Significant
          on { tid } doReturn StringMaxTwoAlphaNumericChars("AB")
        }
    return LocationProcessor(
        messageProcessor,
        preferences,
        LocationRepo(),
        heldSearchAtZero(),
        mock<DeviceMetricsProvider>(),
        mock<WifiInfoProvider>(),
        this,
        Dispatchers.Unconfined,
        SimpleIdlingResource("publishResponseMessageIdlingResource", false),
        SimpleIdlingResource("mockLocationIdlingResource", false),
        false,
        mock<FolloDiagRepo>(),
        noStatus(),
    )
  }

  /**
   * A network fix landing shortly after a high-accuracy one is the case
   * `discardNetworkLocationThresholdSeconds` exists to suppress.
   */
  @Test
  fun `given a recent gps fix, a following network fix is discarded`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val locationProcessor = buildLocationProcessor(messageProcessor, 30)

    val gpsLocation =
        mock<Location> {
          on { time } doReturn 1_000_000L
          on { provider } doReturn "gps"
          on { accuracy } doReturn 5f
        }
    locationProcessor.onLocationChanged(gpsLocation, MessageLocation.ReportType.DEFAULT)

    val networkLocation =
        mock<Location> {
          on { time } doReturn 1_005_000L
          on { provider } doReturn "network"
          on { accuracy } doReturn 500f
        }
    locationProcessor.onLocationChanged(networkLocation, MessageLocation.ReportType.DEFAULT)

    verify(messageProcessor, times(1)).queueMessageForSending(any())
  }

  /**
   * The inverse must not happen: a gps fix converging shortly after a poor network fix is exactly
   * the better location we want to publish. Regression test for
   * https://github.com/owntracks/android/issues/2289
   */
  @Test
  fun `given a recent network fix, a following gps fix is still published`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val locationProcessor = buildLocationProcessor(messageProcessor, 30)

    val networkLocation =
        mock<Location> {
          on { time } doReturn 1_000_000L
          on { provider } doReturn "network"
          on { accuracy } doReturn 500f
        }
    locationProcessor.onLocationChanged(networkLocation, MessageLocation.ReportType.DEFAULT)

    val gpsLocation =
        mock<Location> {
          on { time } doReturn 1_005_000L
          on { provider } doReturn "gps"
          on { accuracy } doReturn 5f
        }
    locationProcessor.onLocationChanged(gpsLocation, MessageLocation.ReportType.DEFAULT)

    verify(messageProcessor, times(2)).queueMessageForSending(any())
  }
}
