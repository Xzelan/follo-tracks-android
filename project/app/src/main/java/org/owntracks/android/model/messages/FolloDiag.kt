package org.owntracks.android.model.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * FOLLO diagnostics (21 Oct 2026): what the app thinks it is doing when a location is published,
 * sent as `fd` on every location post and kept by the FOLLO server, so "why did this phone go
 * quiet?" can be answered afterwards. Short keys, under ~150 bytes; absent fields are omitted.
 *
 * fg 1 front app / 0 background · au always|inuse|denied · pr precise 0/1 · m monitoring mode ·
 * up seconds since the process started · lr launch reason (boot|update|user|service|?) · bt 1 if
 * the foreground service is running · lp battery saver · q positions queued · src trigger name ·
 * pv version of the search boundary the phone holds (the server repeats `poly` only when this differs) ·
 * pn 1 notifications allowed / 0 not · pt push token + pp "fcm" (29 Oct 2026: only when the token changed,
 * on the first three posts after the process started, or once an hour; see PushTokenRepo)
 */
@Serializable
data class FolloDiag(
    @SerialName("fg") val fg: Int? = null,
    @SerialName("au") val au: String? = null,
    @SerialName("pr") val pr: Int? = null,
    @SerialName("m") val m: Int? = null,
    @SerialName("up") val up: Long? = null,
    @SerialName("lr") val lr: String? = null,
    @SerialName("bt") val bt: Int? = null,
    @SerialName("lp") val lp: Int? = null,
    @SerialName("q") val q: Int? = null,
    @SerialName("src") val src: String? = null,
    @SerialName("pv") val pv: String? = null,
    @SerialName("pn") val pn: Int? = null,
    @SerialName("pt") val pt: String? = null,
    @SerialName("pp") val pp: String? = null,
)
