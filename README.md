# FOLLO Tracks (Android)

_The upstream OwnTracks README is kept at [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md)._

FOLLO Tracks is Find Our Lost Loved Ones' build of the OwnTracks Android app
(https://github.com/owntracks/android, Eclipse Public License 1.0). It records
search volunteers' positions during declared search hours, inside the declared
search area, and sends them to FOLLO's own server. Nothing else.

This repository is public because the EPL asks that the source of a changed
program be available to the people who receive it. The licence text is in
`LICENSE.md`; the upstream project's copyright notices are unchanged.

## What is different from OwnTracks

- **One screen.** A live aerial map (you, the search area, FOLLO's markers and
  the other trackers the server shares), a status banner that says in plain
  words whether the phone is sharing, standing by or needs something fixed, and
  a card with your name, tracker ID, when the phone last sent and its battery.
- **No settings.** The phone is set up by tapping a link from FOLLO
  (`https://follo-tracks.pages.dev/app?...` or `follotracks:///config?...`),
  confirmed in one dialog. The configuration must point at a FOLLO server over
  HTTPS; nothing else is accepted. The server then drives everything: when to
  track (Move) and when to stand by (Significant), the search-area fence, map
  markers, the map imagery and the wording of the status line.
- **Removed:** contacts list, waypoint editing, preferences, configuration
  editor/export/import from files, status and log screens, welcome wizard,
  Google Maps (osmdroid with a server-supplied tile URL instead), exported
  intents that let other apps trigger publishes, Play-publishing tooling.
- **Kept as upstream:** the background service, location processing, HTTP
  upload with the offline queue, server commands, geofence waypoints, the
  ongoing notification (without action buttons).
- App ID `au.org.follo.tracks`, link scheme `follotracks:`, so it can live next
  to the real OwnTracks app without the two fighting over links.

Branches: `follo` is FOLLO's app, started from upstream `master` 589e54df
(17 Oct 2026, v2.6.0-beta4). The `upstream` remote is the OwnTracks repository
so its fixes can be merged.

## Server protocol additions

The app sends `User-Agent: FOLLO-Tracks-Android/<build> Owntracks-Android/gms/<build>`.
The FOLLO server answers a location post with the usual OwnTracks command
array plus one element stock apps ignore:

```json
{"_type": "follo", "state": "sharing|outside|nosearch", "search": "Michael Vrankovic search",
 "until": "5:00 pm", "next": "Fri 6 Nov, 8:00 am", "line": "optional full second line",
 "name": "Volunteer Name", "team": "Blue", "tile": "https://.../{z}/{y}/{x}"}
```

`GET /appcfg?u=&tid=&k=` returns the OwnTracks configuration JSON for a signed
link (plus `folloName`); `GET /app?...` is the page behind the link in Messages.

## Building

```
cd project
./gradlew :app:assembleGmsDebug          # debug APK, installable with adb
FOLLO_KEYSTORE=... FOLLO_KEYSTORE_PASS=... ./gradlew :app:bundleGmsRelease   # signed AAB for Play
```

Needs JDK 21 and the Android SDK (compileSdk 36). The signing key is never in
the repository.
