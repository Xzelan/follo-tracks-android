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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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

  /**
   * Edge hold (Alex's walk 2 Nov 2026): in Move a fix just outside (the circle is 1000 m; 0.00901° ≈ 1002 m) is kept on
   * the phone and tracking goes on. The next fix inside is sent; the held one, still outside, is dropped, never sent.
   */
  @Test
  fun `a fix just outside is held, not sent, and the phone keeps tracking`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val preferences = movingPreferences()
    val lp = outsideProcessor(messageProcessor, preferences)

    lp.onLocationChanged(fixAt(0.00901, 1_000_000L), MessageLocation.ReportType.DEFAULT) // 1 m out: held
    lp.onLocationChanged(fixAt(0.00902, 1_005_000L), MessageLocation.ReportType.DEFAULT) // 2 m out: held
    lp.onLocationChanged(fixAt(0.0089, 1_010_000L), MessageLocation.ReportType.DEFAULT) // back inside: sent

    val captor = argumentCaptor<MessageBase>()
    verify(messageProcessor, times(1)).queueMessageForSending(captor.capture())
    val sent = captor.firstValue as MessageLocation
    assertEquals(0.0089, sent.latitude, 1e-9)
    verify(preferences, never()).monitoring = MonitoringMode.Manual
  }

  /** 50 m or more beyond the edge (0.0095° ≈ 1056 m): every held fix dropped, standing by with the "out" hello. */
  @Test
  fun `50 m outside drops the held fixes and stands the phone by`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val preferences = movingPreferences()
    val lp = outsideProcessor(messageProcessor, preferences)

    lp.onLocationChanged(fixAt(0.00901, 1_000_000L), MessageLocation.ReportType.DEFAULT) // held
    lp.onLocationChanged(fixAt(0.0095, 1_005_000L), MessageLocation.ReportType.DEFAULT) // 55 m out: stand by
    lp.onLocationChanged(fixAt(0.0089, 1_010_000L), MessageLocation.ReportType.DEFAULT) // inside again: only this one

    val captor = argumentCaptor<MessageBase>()
    verify(messageProcessor, times(2)).queueMessageForSending(captor.capture())
    assertTrue(captor.allValues[0] is MessageHello)
    assertEquals(0.0089, (captor.allValues[1] as MessageLocation).latitude, 1e-9)
    verify(preferences, times(1)).monitoring = MonitoringMode.Manual
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

  // ── "Share everywhere I go" (Alex, 22 Nov 2026; NEXT_BUILD_SPEC_20261122.md §D) ─────────────────────────────

  @org.junit.After
  fun everywhereOffAgain() {
    org.owntracks.android.support.FolloEverywhere.set(false)
    org.owntracks.android.support.FolloEverywhere.noticeDone()
  }

  /** Standing by (Manual) 11 km outside the only circle, with no window open at all: every rule says "not sent". */
  private fun closedStatus() =
      mock<FolloStatusRepo> {
        on { status } doReturn
            MutableStateFlow<FolloStatusRepo.Status?>(
                FolloStatusRepo.Status(MessageFollo().apply { wins = listOf(listOf(JsonPrimitive("TEST"), JsonPrimitive(0), JsonPrimitive(1))) }, 0L))
      }

  private suspend fun TestScope.processorWith(messageProcessor: MessageProcessor, preferences: Preferences, status: FolloStatusRepo, repo: LocationRepo = LocationRepo()) =
      LocationProcessor(
          messageProcessor,
          preferences,
          repo,
          heldSearchAtZero(),
          mock<DeviceMetricsProvider>(),
          mock<WifiInfoProvider>(),
          this,
          Dispatchers.Unconfined,
          SimpleIdlingResource("publishResponseMessageIdlingResource", false),
          SimpleIdlingResource("mockLocationIdlingResource", false),
          false,
          mock<FolloDiagRepo>(),
          status,
      )

  private fun standingByPreferences() =
      mock<Preferences> {
        on { maxImplausibleSpeedKmh } doReturn 0
        on { ignoreInaccurateLocations } doReturn 0
        on { discardNetworkLocationThresholdSeconds } doReturn 0
        on { fusedRegionDetection } doReturn false
        on { monitoring } doReturn MonitoringMode.Manual
        on { setupCompleted } doReturn true
        on { tid } doReturn StringMaxTwoAlphaNumericChars("AB")
      }

  @Test
  fun `with share everywhere on, a fix far outside every circle with no window open is sent and says ev 1`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val preferences = standingByPreferences()
    val lp = processorWith(messageProcessor, preferences, closedStatus())

    lp.onEverywhereOn(nowMs = 0L) // (the made-up fixes here are dated 1970)
    verify(preferences).monitoring = MonitoringMode.Move // the phone puts itself in Move, at once
    lp.onLocationChanged(fixAt(0.1, 1_000_000L), MessageLocation.ReportType.DEFAULT)
    lp.onLocationChanged(fixAt(0.2, 1_005_000L), MessageLocation.ReportType.DEFAULT)

    val captor = argumentCaptor<MessageBase>()
    verify(messageProcessor, times(2)).queueMessageForSending(captor.capture())
    for (m in captor.allValues) assertEquals(1, (m as MessageLocation).ev)
    assertEquals(0.2, (captor.secondValue as MessageLocation).latitude, 1e-9)
    verify(preferences, never()).monitoring = MonitoringMode.Manual // and never stands itself by
  }

  @Test
  fun `with share everywhere off the same fix is not sent, and nothing says ev`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val lp = processorWith(messageProcessor, movingPreferences(), closedStatus())

    lp.onLocationChanged(fixAt(0.1, 1_000_000L), MessageLocation.ReportType.DEFAULT) // outside: the hello
    lp.onLocationChanged(fixAt(0.0, 1_005_000L), MessageLocation.ReportType.DEFAULT) // inside the circle, no window: not sent

    val captor = argumentCaptor<MessageBase>()
    verify(messageProcessor, times(1)).queueMessageForSending(captor.capture())
    val hello = captor.firstValue as MessageHello
    assertEquals("out", hello.src)
    assertNull(hello.ev)
  }

  @Test
  fun `a check-in under share everywhere posts its fix wherever the phone is`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val lp = processorWith(messageProcessor, standingByPreferences(), closedStatus())
    lp.onEverywhereOn(nowMs = 0L)

    assertTrue(lp.onCheckInLocation(fixAt(0.1, 1_000_000L), LocationProcessor.CheckInSource.OPEN))
    lp.onCheckInWithoutFix(LocationProcessor.CheckInSource.PUSH) // no fix came: the hello it sends today, saying ev

    val captor = argumentCaptor<MessageBase>()
    verify(messageProcessor, times(2)).queueMessageForSending(captor.capture())
    assertEquals(1, (captor.firstValue as MessageLocation).ev)
    assertEquals(1, (captor.secondValue as MessageHello).ev)
  }

  @Test
  fun `turning share everywhere off outside a search stands the phone by with a location-free hello, and nothing more leaves`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val preferences = movingPreferences()
    whenever(preferences.setupCompleted).thenReturn(true)
    val repo = LocationRepo()
    val lp = processorWith(messageProcessor, preferences, noStatus(), repo)
    lp.onEverywhereOn(nowMs = System.currentTimeMillis() - 1_000)
    lp.onLocationChanged(fixAt(0.1, System.currentTimeMillis()), MessageLocation.ReportType.DEFAULT) // sent, ev 1

    lp.onEverywhereOff()
    assertFalse(org.owntracks.android.support.FolloEverywhere.isOn)
    lp.onLocationChanged(fixAt(0.1, System.currentTimeMillis() + 5_000), MessageLocation.ReportType.DEFAULT) // judged by the gate again

    val captor = argumentCaptor<MessageBase>()
    verify(messageProcessor, times(2)).queueMessageForSending(captor.capture())
    assertEquals(1, (captor.firstValue as MessageLocation).ev)
    val hello = captor.secondValue as MessageHello
    assertEquals("out", hello.src)
    assertNull(hello.ev)
    verify(preferences, org.mockito.kotlin.atLeastOnce()).monitoring = MonitoringMode.Manual
  }

  @Test
  fun `turning share everywhere off inside a search with its window open just carries on, without ev`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val preferences = movingPreferences()
    whenever(preferences.setupCompleted).thenReturn(true)
    val lp = processorWith(messageProcessor, preferences, noStatus())
    lp.onEverywhereOn(nowMs = System.currentTimeMillis() - 1_000)
    val t = System.currentTimeMillis()
    lp.onLocationChanged(fixAt(0.001, t), MessageLocation.ReportType.DEFAULT)

    lp.onEverywhereOff(byServer = true)
    assertTrue(org.owntracks.android.support.FolloEverywhere.serverOffNotice.value)
    lp.onLocationChanged(fixAt(0.002, t + 5_000), MessageLocation.ReportType.DEFAULT)

    val captor = argumentCaptor<MessageBase>()
    verify(messageProcessor, times(2)).queueMessageForSending(captor.capture())
    assertEquals(1, (captor.firstValue as MessageLocation).ev)
    assertNull((captor.secondValue as MessageLocation).ev)
    verify(preferences, never()).monitoring = MonitoringMode.Manual
  }

  /** Spec §H (found in the live check, 22 Nov 2026): the switch-on check-in had sent a fix taken 2 s before the OK. */
  @Test
  fun `a fix taken before the OK is never sent under the mode, one at or after it is`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val preferences = movingPreferences()
    val lp = processorWith(messageProcessor, preferences, closedStatus())
    val ok = 2_000_000L
    lp.onEverywhereOn(nowMs = ok)

    lp.onLocationChanged(fixAt(0.1, ok - 1_000), MessageLocation.ReportType.DEFAULT) // 1 s before the OK, outside: dropped
    assertFalse(lp.everywhereCovers(fixAt(0.1, ok - 1_000)))
    verify(messageProcessor, never()).queueMessageForSending(any())
    verify(preferences, never()).monitoring = MonitoringMode.Manual // and the phone, in the mode, is not stood by for it

    lp.onLocationChanged(fixAt(0.1, ok), MessageLocation.ReportType.DEFAULT) // at the OK: sent
    lp.onLocationChanged(fixAt(0.1, ok + 5_000), MessageLocation.ReportType.DEFAULT)
    val captor = argumentCaptor<MessageBase>()
    verify(messageProcessor, times(2)).queueMessageForSending(captor.capture())
    for (m in captor.allValues) assertEquals(1, (m as MessageLocation).ev)
  }

  @Test
  fun `a fix taken before the OK is judged by the normal gate like any other fix`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val lp = processorWith(messageProcessor, movingPreferences(), noStatus()) // the TEST window is open
    val ok = 2_000_000L
    lp.onEverywhereOn(nowMs = ok)

    lp.onLocationChanged(fixAt(0.001, ok - 1_000), MessageLocation.ReportType.DEFAULT) // inside the search: goes, without ev
    // a check-in holding only the fix from before the OK, outside: the location-free hello, never the position
    assertTrue(lp.onCheckInLocation(fixAt(0.1, ok - 1_000), LocationProcessor.CheckInSource.OPEN))

    val captor = argumentCaptor<MessageBase>()
    verify(messageProcessor, times(2)).queueMessageForSending(captor.capture())
    assertNull((captor.firstValue as MessageLocation).ev)
    assertEquals(1, (captor.secondValue as MessageHello).ev)
  }
}
