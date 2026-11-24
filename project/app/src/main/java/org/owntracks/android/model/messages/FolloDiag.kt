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
 * on the first three posts after the process started, or once an hour; see PushTokenRepo) · v the app's version
 * as the heading shows it, "0.1.32" or "0.1.32 (1)" (9 Nov 2026 review: fd had only pv, the boundary version)
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
    @SerialName("v") val v: String? = null,
    /** Version of the search grids the phone holds (10 Nov 2026). */
    @SerialName("gv") val gv: String? = null,
    /** Offline map: percent of its tiles saved, 0–100; absent when there is nothing to save. */
    @SerialName("of") val of: Int? = null,
    /** 1 while the app is turned Off with the power button (13 Nov 2026); absent otherwise. */
    @SerialName("off") val off: Int? = null,
    /** FP066 (23 Nov 2026): how many approach rings the phone is in (no names, no distances). */
    @SerialName("ar") val ar: Int? = null,
)
