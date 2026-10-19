# Brainrot

<img src="app/src/main/res/drawable-nodpi/ic_launcher_brain.png" width="96" align="right" alt="Brainrot icon">

An Android app that counts the Instagram Reels you swipe through, and shows you a brain that rots
a little more with every one.

- **Counts reels, on the phone.** An accessibility service watches which list scrolled and where
  it landed. It never reads captions or messages.
- **Floating brain over Reels.** While Reels is open, a draggable brain shows today's count. It
  drips with every reel and visibly rots as the number climbs.
- **Home-screen widget.** Today's count and the brain's current state, on your home screen.
- **Today screen.** The count, time spent, sittings, reels per hour, and seconds per reel.
- **Friends (optional).** Connect your Instagram username, share an invite link, and you and your
  friends see each other's daily counts live. Nothing leaves the phone until you connect.

---

## The rot

The brain has seven stages, driven by today's count. Counting resets at local midnight.

| Reels today | 0 | 10 | 25 | 50 | 100 | 175 | 250+ |
|---|---|---|---|---|---|---|---|
| Stage | Fresh | Bruised | Foggy | Fried | Mushy | Rotting | Brain rot |
| | <img src="service/src/main/res/drawable-nodpi/brain_stage_0.png" width="64"> | <img src="service/src/main/res/drawable-nodpi/brain_stage_1.png" width="64"> | <img src="service/src/main/res/drawable-nodpi/brain_stage_2.png" width="64"> | <img src="service/src/main/res/drawable-nodpi/brain_stage_3.png" width="64"> | <img src="service/src/main/res/drawable-nodpi/brain_stage_4.png" width="64"> | <img src="service/src/main/res/drawable-nodpi/brain_stage_5.png" width="64"> | <img src="service/src/main/res/drawable-nodpi/brain_stage_6.png" width="64"> |

The thresholds live in one place, [`BrainRot.kt`](service/src/main/java/com/reeltracker/service/BrainRot.kt),
so the overlay, the widget and the app always agree.

### The floating counter

| When | What it does |
|---|---|
| Idle | The brain breathes |
| A reel is counted | It squishes and sheds a drop: blue sweat while it's pink, green slime from stage 4 |
| A new stage is reached | Lightning, a shockwave ring, a shake, and the brain swaps to the next sprite |
| Stages 5 and 6 | Smoke curls off the top, continuously |

- **Drag it** anywhere. On release it slides to the nearest side edge, and the position is
  remembered for next time. A tap without dragging wobbles it.
- **Swiping is never blocked.** The overlay window is only as big as the brain (92 × 104 dp).
  Touches outside it go straight to Instagram.
- It appears when you enter Reels and disappears when you leave.

### The home-screen widget

Shows the brain for today's stage, the count, and the stage name. Tapping it opens the app.

- Add it from the app (**Add to home screen** under the count), or long-press the home screen →
  Widgets → Brainrot.
- It updates on every counted reel. When nothing is being counted, Android's minimum refresh
  (30 minutes) is what rolls it back to 0 after midnight, so the reset can lag by up to half an hour.

---

## Install and set up

1. **Build and install** (see [Building](#building)), or install a built APK with
   `adb install -r app-debug.apk`.
2. **Turn on the service:** Settings → Accessibility → Installed apps → **Brainrot** → On. The app's
   status pill (top right) opens this screen for you, and turns green ("Tracking") once it's running.
3. **Let it run in the background:** Settings → Apps → Brainrot → Battery → **Unrestricted**.
   Without this, many phones (OPPO/OnePlus/Xiaomi in particular) stop the service after a while, and
   the app shows "Not running".
4. Open Instagram, go to Reels, and swipe. The brain appears top-right.

---

## How counting works

```
Instagram ──AccessibilityEvent──▶ ReelAccessibilityService ──UiSignal──▶ Detector ──Decision──▶ ReelRepository ──▶ Room
                                     (service, Android)                  (core-detect, pure)         (core-data)
                                            │                                                          │
                                            └──── EnterReels / ExitReels ──▶ overlay         Flow<Int> ─┴─▶ overlay, widget, Today screen
```

1. **Only three event types are subscribed to:** a view scrolled, a window changed, a tab was
   selected ([`reel_service.xml`](service/src/main/res/xml/reel_service.xml)). The service reads no
   view text and never walks the view tree. It reads a scrolling view's id and the pager position
   Android already puts on the event.
2. **`SignalMapper`** turns each event into a small `UiSignal`. Everything from other apps is
   dropped before the event's source is even read.
3. **`Detector`** is a pure state machine: *outside Reels* ↔ *in Reels*. It enters when the Reels
   tab is selected or the Reels pager scrolls. It exits when any other tab, window or app takes over.
   **Accuracy policy:** when unsure, exit. A missed reel makes the number a little low; a phantom
   reel would make it wrong forever.
4. **`IndexStrategy`** confirms a reel the first time the pager settles on a page index it hasn't
   seen:
   - an abandoned half-swipe never changes the index, so it doesn't count;
   - swiping back lands on an index already seen, so it doesn't count again;
   - a refreshed feed restarts indices, and is detected (item count shrank *and* index went
     backwards) and started as a new epoch rather than re-counting.
5. **`ReelRepository`** stores one row per reel, with the unique key `(sessionId, key)`, so a
   duplicate is dropped by the database rather than by logic. No counter is ever stored: today's
   count, hourly chart and sittings are all computed from event times when they're read. That means
   midnight isn't an event, and nothing can drift.

### Privacy

- Counting is local: every event lives in a Room database (`reels.db`). No analytics.
- Nothing is sent until you connect for friends. Then the app sends your Instagram username and
  name (read once off your own profile screen, only while you're connecting) and today's totals
  (reel count and seconds in Reels). See [Friends](#friends).
- Never reads captions, comments, messages, or anyone else's profile.
- The debug export (below) contains event types, view ids, positions and hashes, never text.

---

## Friends

Supabase, called directly from the app with the public anon key. No other backend.

1. **Connect.** The app arms a one-shot capture and opens Instagram. When the Profile tab is
   selected (so it's your own profile, not someone else's), the service reads `action_bar_title`
   and `profile_header_full_name_above_vanity` by id, then brings Brainrot back to confirm
   ([`ProfileReader.kt`](service/src/main/java/com/reeltracker/service/ProfileReader.kt)). This is
   a capture, not proof of ownership: Instagram offers no way to verify without its API.
2. **Identity.** Each install signs in to Supabase anonymously. `upsert_profile` creates the profile
   and an 8-character invite code.
3. **Invite.** The share link is `https://brain-rot-drab.vercel.app/?invite=CODE`. The site copies it
   when the APK is downloaded, and the app offers it from the clipboard on first launch. If the app
   is already installed, the link opens it directly (App Link, verified by
   [`docs/.well-known/assetlinks.json`](docs/.well-known/assetlinks.json)). `redeem_invite` makes
   the friendship both ways.
4. **Live counts.** The service sends today's *totals* (not increments) at most every 2 s via
   `push_stats`, which keeps the larger value, so retries can't double-count. While the app is
   open it subscribes to Realtime changes on `daily_stats`; RLS limits those to you and your friends.

The whole database is in [`supabase/schema.sql`](supabase/schema.sql): tables, RLS, and the functions.
Clients can only read; every write goes through a function that touches the caller's rows only.

**Setup:** run `schema.sql` in the SQL editor, turn on *Authentication → Sign In / Providers →
Allow anonymous sign-ins*, and add the keys to `local.properties`:

```properties
supabase.url=https://<ref>.supabase.co
supabase.anonKey=<anon key>
```

**Limits:** clearing the app's data or reinstalling creates a new anonymous user, and the old
friends are gone. With no server-side rate limiting, a determined user can fake their own count
(capped at 10,000 a day) or try to guess invite codes.

---

## Building

**Requirements**
- JDK 17
- Android SDK with platform 35 (`compileSdk 35`, `minSdk 29`, i.e. Android 10+)
- Gradle 8.9 (the wrapper downloads it)

`local.properties` points at your SDK:

```properties
sdk.dir=D\:/android-sdk
```

**Commands** (use `./gradlew` on macOS/Linux)

```bash
gradlew.bat :app:assembleDebug
```

```bash
gradlew.bat :app:installDebug
```

```bash
gradlew.bat :core-detect:test :core-model:test
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

---

## Project structure

| Module | What's in it | Android? |
|---|---|---|
| `core-model` | `UiSignal`, `Decision`, `DayStats` (sittings, hourly buckets) | No, pure Kotlin |
| `core-detect` | `Detector`, `IndexStrategy`, `ReelsContext`, `SignalMapper`, `SignalRecorder`, plus replay tests | No, pure Kotlin |
| `core-data` | Room database, `ReelDao`, `ReelRepository` | Yes |
| `core-sync` | Supabase over OkHttp: anonymous auth, profile, invites, friends, Realtime (`SyncRepository`) | Yes |
| `service` | `ReelAccessibilityService`, the overlay (`OverlayController`, `BrainCounterView`), the widget (`ReelWidgetProvider`), `BrainRot` stages, sprites | Yes |
| `app` | Compose UI: `MainActivity`, `TodayScreen`, onboarding and friends (`FriendsScreens`), `HourlyChart`, theme, launcher icon | Yes |
| `spike` | M0 forensic logger, a separate app (`com.reeltracker.spike`) | Yes |

`core-detect` must never depend on Android. That boundary is what lets the detector run against
recorded captures in plain JVM tests.

Key files:

- [`ReelsContext.kt`](core-detect/src/main/kotlin/com/reeltracker/detect/ReelsContext.kt): Instagram's view ids. **This is what breaks when Instagram updates.**
- [`BrainRot.kt`](service/src/main/java/com/reeltracker/service/BrainRot.kt): stage thresholds, names and sprites.
- [`BrainCounterView.kt`](service/src/main/java/com/reeltracker/service/BrainCounterView.kt): the overlay's animations.
- [`OverlayController.kt`](service/src/main/java/com/reeltracker/service/OverlayController.kt): the overlay window, dragging, saved position.
- [`ReelWidgetProvider.kt`](service/src/main/java/com/reeltracker/service/ReelWidgetProvider.kt): the home-screen widget.

---

## When Instagram changes and counting stops

Counting depends on Instagram's internal view ids (`clips_viewer_view_pager`, `clips_tab`, and
`*_tab`), last verified on Instagram 447.0.0.55.81, OnePlus CPH2467, Android 15. An Instagram
update can rename them. The usual symptom: "Last one counted" stops moving while you're clearly
scrolling Reels.

1. Reproduce it: open Reels, swipe through a few, leave Reels.
2. In Brainrot, tap **Export debug capture**. It writes `Download/reel-capture-<time>.json` with the
   last 2,000 signals and what the detector decided for each.
3. Look at the `sourceId` values on `Scrolled` and `Selected` entries to find the new pager and tab
   ids, and update [`ReelsContext.kt`](core-detect/src/main/kotlin/com/reeltracker/detect/ReelsContext.kt).
4. **Add the capture to the regression corpus.** Copy it to
   `core-detect/src/test/resources/corpus/`, named `<date>__ig<version>__<device>__expect<N>.json`
   where N is the number of reels you actually watched, e.g.
   `2026-10-02__ig450.0.0.12.34__oneplus-cph2467__expect12.json`. `DetectorReplayTest` replays every
   file there and asserts its count, so a fix for one Instagram version can't silently break an
   older one.
5. Run the tests (`gradlew.bat :core-detect:test`).

If the export isn't enough, e.g. Instagram stopped reporting page indices entirely, use the
**spike** app. It logs every Instagram accessibility event (with any text reduced to a hash and
length) to a JSONL file in its app storage. Summarise a capture with:

```powershell
pwsh tools/m0-summary.ps1 captures/capture-<timestamp>.jsonl
```

It reports, for each scrolling view, whether Instagram populates `toIndex`/`itemCount`, the
information `IndexStrategy` depends on. A new counting method would be a second `DetectionStrategy`.
The detector already picks the first strategy whose `supports()` accepts the signals it sees.

---

## Customising

- **Stage thresholds and names:** `THRESHOLDS` and `LABELS` in `BrainRot.kt`.
- **Sprites:** `service/src/main/res/drawable-nodpi/`. `brain_stage_0` … `brain_stage_6` are the
  stages; `fx_*` are the effects (drops, drips, smoke, bolt, ring, crack, tornado). All are
  transparent PNGs cut from the original sprite sheet; replace any file with one of the same name.
- **Overlay size and default position:** `W`, `H` and the brain constants in `BrainCounterView`;
  the default spot in `OverlayController.savedPosition()`.
- **Launcher icon:** `app/src/main/res/drawable-nodpi/ic_launcher_*.png`, an adaptive icon
  (background, foreground, and a monochrome layer for themed icons).

---

## Known limitations

- **Instagram only.** YouTube Shorts and others would each need their own `ReelsContext` and probably
  their own strategy.
- **Verified on one device and Instagram version** (see above). Other builds may use other view ids.
- **The service can be killed** by aggressive battery managers. Set battery use to Unrestricted, and
  watch the status pill: "Not running" means Android stopped it. Toggling it off and on in
  Accessibility settings restarts it.
- **Widget midnight reset** can lag up to 30 minutes when nothing is being counted (Android's
  minimum widget refresh).
- **Package name** is still `com.reeltracker` from the project's original name. Changing it would
  install Brainrot as a separate app and lose existing history.
