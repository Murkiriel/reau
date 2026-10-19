# Changelog

All notable changes to REau are listed here, newest first, in the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format. The app follows semantic versioning in its `0.y.z` form
(no 1.0 yet, so anything may still change between minor versions): release builds are called `0.1.0`, and debug builds add
the build date and time after `-dev+` (`0.1.0-dev+YY.MM.DD-HH.MM`, for example `0.1.0-dev+26.10.19-09.37`). Versions are listed
newest first; the days before 0.1.0 are kept below it as the project's history, each gathering what changed that day (days
with no change visible to people who use the app are not listed).

Português (pt-BR): [CHANGELOG.pt-br.md](CHANGELOG.pt-br.md)

## [0.1.0] - 2026-10-19

### Added
- **First release.** REau takes the phone's Android Auto, with Waze, to the Royal Enfield Tripper Dash over the dash's own
  Wi-Fi.
- **Two editions**, which install side by side: the **Lite** (recommended; no Accessibility service, you switch Android
  Auto's head unit server on by hand) and the **full** one (an Accessibility service switches the server on by itself).
- **Calls by joystick**: answer, decline and hang up from the dash's buttons.
- **Bike data** shown in the app, as the dash reports them.
- **English and Portuguese**, following the phone's language or chosen in the settings.
- **No account and no data collection**: everything stays on the phone.

## 2026-10-19

### Changed
- The **documentation** follows the changelog's rule: `README.md` and `TERMS_OF_USE.md` are the English texts and the Portuguese ones are `README.pt-br.md` and `TERMS_OF_USE.pt-br.md` (the latter was `TERMOS_DE_USO.md`). The README now says the diagnostic log is on by default, that an APK is published, and how to allow restricted settings (Android 13 or newer); it says "the dash's screen" where it said "glass", leaves REau's source address to the Credits screen and drops the paragraph on the experimental mode for Android Auto 17.3 or older; it also links the changelog. The README now says the **Lite is the recommended edition** (no Accessibility service; the cost is turning on Android Auto's server by hand each time).
- The **terms of use** name the browser feature as the app does ("Turn on the network server", it was "View in the browser") and quote the "I accept" button as it reads in the English app; the English text also says "is off by default" where it said "comes off"; they describe the project as created by someone who likes motorcycles and is a free-software enthusiast; and they now say the compatibility was verified with firmware 13.10, the one the dash in use reports (it said 11.63). The terms version stays 2.
- The **Credits** and the `NOTICE` now point open-dash to its source project (`subtlesayak/open-dash`) and name its holder, "Subtle Sayak and contributors": the fork they cited is no longer online, and the dash output sequence credited there comes from a version published before that project's cleanup of 2026-09-21.

### Fixed
- The **Weather** shortcut no longer stays on "Fetching…" forever when there is no location, no internet or no answer from the service: it now says why, the same reason its own screen gives.
- The full edition's **"Android Auto does not respond"** warning gets an **Open Accessibility** button when the service is off (the text already told you to enable it, but there was no way to it).
- The notice that **Android Auto's server was left on** no longer blames only a locked phone: in the full edition it also names REau's Accessibility being off (the Lite keeps the text without Accessibility).
- **Turning off** with the dash connected no longer writes a false warning to the diagnostic log that the dash socket failed.
- **Turning off** no longer logs a video codec error ("Pending dequeue output buffer request cancelled"): the decoder now waits for its output thread to stop before it shuts down.
- Leaving the **Android Auto screen** no longer logs a warning that the phone preview failed: it just switches off, as always, and the dash carries on.
- A **rare hiccup in the dash picture** is fixed: when Android Auto cut a video frame so that its last piece was a single byte, REau threw it away, the frame came out broken and REau asked Android Auto for a new picture (a brief stop of the image).

## 2026-10-18

### Added
- The **Bike data** screen gets a **Range** section: how far the bike can still go on the fuel it has, as the dash itself counts it (code `0C07`, which it sends about every 5 seconds), with the time it was read. It shows in kilometres, or in miles with the kilometres in brackets if the dash is set to miles; with no reading it says "Not reported yet" and shows no number.
- The **terms of use** only show "I accept" and "I do not accept" once read to the end: until then the foot has a "Scroll to the end" button, which rolls the text on continuously, at a reading pace (1 second), to the end; touching the screen stops the roll (or just scroll the text there).
- The **REau Lite** icon gets the word "Lite" written over it, in white with an outline in the app's background grey, at the bottom right, to tell it from REau in the app list (in the themed icon too).

### Changed
- The **terms of use** move to **version 2** (whoever accepted version 1 accepts again): the sentence about the dash protocol now says that the maker neither documents nor supports it and that it was reconstructed by open community projects and by compatibility testing, verified with firmware 11.63.
- The **"REau and your data"** notice now says everything that stays on the phone: besides the log and the recordings, the dash's Wi-Fi name and password and what the dash has already reported (encrypted with a key kept by Android) and, if you turn them on, the rounded position and the last weather.
- The `NOTICE` and the **Credits** screen now say, project by project, what came from each (better-dash, NorthStar, recon-dash, open-dash and the headunit lineage) and that the names mentioned belong to their owners.
- The texts that spoke of the dash's factory password now say only "the dash's default password", without the number, and the dash Wi-Fi help warns: if more than one dash is nearby, check the name and use **Forget**.

### Fixed
- **First connection to the dash's Wi-Fi.** It no longer gets lost in the 30-second window: the first time REau asks for that network, Android asks in a dialog of its own ("Connect to device") whether it may connect, and if the tap on **Connect** took longer than the request, Android showed "Something went wrong" and started over (about 5 minutes on a fresh install). While REau has never joined that network on this phone, the request now waits up to **2 minutes**, and the screen and the notification say "Android may ask whether REau can connect to the dash: tap Connect"; once the network has been joined (remembered with the dash's data, erased by **Forget**), the old waits return (30 seconds on the first try of each start, 10 on reconnections). That notice shows only when the phone's last network scan sees the dash, and REau asks Android for the network only after it has seen the dash nearby, so with the bike off or away the "Connect to device" dialog no longer pops up again and again. The ride log also says when each request was let go, the age of the last scan and whether the network was already approved.
- **Turning off and on again.** Between the tap on **Turn off** and the end of the shutdown the screen and the notification say **"Turning off…"** and the Turn on button is disabled, instead of looking dead; it goes back to "Stopped" even if one step of the shutdown fails. Tapping **Turn on** right after no longer tells you to force stop Android Auto: it takes up to about 1 minute to close the previous session and, meanwhile, accepts the connection without answering. For up to 2 minutes the warning says Android Auto is closing the previous session, that REau tries again by itself and that, to skip the wait, you can stop and start the head unit server again in its settings. The notice no longer flips with "Connecting" on every round, and the 2 minutes also hold if REau is closed and opened again within them. Meanwhile the full edition no longer stops and starts the head unit server by itself (that could bring Android Auto down with the "Already connected" error); only if the time runs out with Android Auto still mute does it try, once, the restart of before.
- **Mute Android Auto server and both editions installed.** When Android Auto accepts the connection and stays mute, REau no longer spends its one restart of the server while the accessibility service is not connected yet: when the service connects (and the server is still mute), the full edition restarts it by itself. On the **Lite**, and on the full edition without the service on, the warning, the line under the title and the notification tell the rider to stop and start the head unit server in Android Auto's ⋮ menu instead of "Force stop". With both editions installed, the screen now says the other edition may be using Android Auto and asks you to turn it off, "Android Auto isn't responding" no longer flickers to "Connecting" on every retry, and a session that ends outside REau (the other edition's) is shown as "Closing the previous session" for up to 2 minutes. The 120 s wait for a previous session that does not close stays, and the log says when it runs out.
- **Lite and Accessibility texts.** In the **Lite**, the terms of use no longer speak of the accessibility service (it has none), and the "Android Auto server off" warning no longer talks about Accessibility or shows the "Open Accessibility" button: it says only the manual step (Android Auto developer options, the ⋮ menu, "Start head unit server"), that the server turns off when the session ends and has to be turned on again every time, and keeps the button that opens Android Auto settings. In the full edition, the ask to enable REau in Accessibility appears once instead of two nearly identical sentences (none with the service connected), and after you enable it and come back to the app, REau tries to turn the server on by itself as soon as the service connects, if the warning was up because of it.
- **Other warnings and counts.** "Android Auto has no picture" (and the advice to force-stop Android Auto) shows only with the dash connected; with the bike or dash off REau stays on "Connecting", with no alarm. Until the dash has ever connected in the session, REau says "Looking for the dash" instead of "Dash lost, trying again" ("lost" only appears after a connection that really existed). Without Android Auto installed, the "Open Android Auto settings" buttons say so and take you to its Google Play page (or the web page when there is no store app), and if nothing can open, a notice appears instead of nothing. In **Permissions**, "N missing" and "Ask for the missing ones (N)" only count the permissions of the functions switched on in the settings; those of switched-off functions stay in the list, marked optional.
- The **video header** reader (the H.264 SPS) now finds the right size when the header carries scaling lists (high profiles) or `pic_order_cnt_type` 1, which used to give a wrong size; an empty header, or one cut off before the size, now counts as "no data" instead of an invented size.

## 2026-10-17

### Added
- **Two editions of REau**, which install side by side on the same phone (but must not run at the same time). The **full** one (`com.reau.app`) is the usual one. The **Lite** (`com.reau.app.lite`, "REau Lite") has no accessibility service, and no app-menu fit of its own, which depends on it: with Android Auto 17.4 or newer you switch the head unit server on by hand; with 17.3 or older, REau asks Android Auto to dial it (experimental, proven only up to the opening of the session) and says "Android Auto didn't call REau" if it does not. `./gradlew assembleRelease` builds both APKs.

### Changed
- The **Terms of use** say that only the full edition uses the accessibility service (the Lite does not).
- In the navigation's reduced profile the dash video also drops to 200 kbps (along with the 2 frames per second), and
  goes back to 400 kbps when the profile returns to normal.
- **Phone status**, **Album art**, **Real route on the dash** and **Weather on the dash** now apply right away, with the projection on (the first three get the LIVE tag); before, they only applied the next time you turned it on.
- **Stuck-picture warning:** with the bike moving on a route and Android Auto sending no new picture for 15 seconds, the dash shows a **PICTURE STUCK** badge at the bottom and the notification and the main screen say "No new picture" (before, the dash repeated the last picture and looked alive). It goes as soon as the picture is back. Stopped or with no route there is no warning, since Android Auto is genuinely quiet then.
- The **Credits** screen now shows, at the top, the address of REau's own source code (a tap copies it), as the AGPL asks; the Fraunces font's year is now the one the font itself carries (2020).
- The solar clock (which decides the light or dark theme by sunrise and sunset) was rewritten from NOAA's public equations, with astronomical reference tests; `NOTICE` gained the files it was missing and lost one that no longer exists.
- **Use location** also applies right away: with the sun-based theme and the projection on, the dash takes the place’s day or night as soon as you flip the switch (it gets the LIVE tag); before, it took up to a minute.
- The **Terms of use** and the **Credits** move from the settings to the end of the "REau and your data" screen, next to what the app is and what it does with your data.
- The help texts (the **i** button) were reviewed on every screen: the video profiles, the wallpaper, the debug
  recordings, the fit mode, the margins, the known dash and the last reading of the bike data gained help, and
  several texts were corrected or completed.

### Removed
- The help texts of "Now playing" and "Who’s calling", since they only repeated the line below (the line now says more).

### Fixed
- When the bike's Wi-Fi comes back after being gone, the dash now returns at once, instead of waiting up to a minute for
  the next try.
- The ride summary in the log counts as a "dash drop" only a dash that was connected and went away; the reconnection
  tries that fail are shown apart, as `retries dash`.
- Changing the language in Settings no longer flashes the screen: the texts change at once, without the screen being rebuilt (Android 13 or newer).
- The **LIVE** tag in Settings now appears only on the options that really apply with the projection on: language, theme,
  answering calls, the diagnostic log, the detailed log and deleting the oldest automatically. The help texts of
  "Real route on the dash", "Use location" and "When each setting applies" now say when each one applies.

## 2026-10-16

### Added
- **First version presented to the public** (released as 0.1.0 on 2026-10-19). REau brings the phone's Android Auto, with Waze, to the Royal Enfield
  Tripper Dash, over the dash's Wi-Fi and with the phone screen off.
- **Calls by joystick** (the "Answer calls" switch), in both dash modes and for both carrier calls and voice-app calls:
  while ringing, left answers and right or center declines; during a call, center hangs up and up and down change the
  volume. Answering and declining now work on the very first press on the dash's call screen. The "Android Auto on the
  dash" notification and Settings now say that center hangs up the call.
- **English** as the app's language, besides Portuguese: the app follows the phone's language, and the settings have an
  Automatic / Português / English switch. English is the default for anyone using another language.
- **"Call" tab** on the joystick page, read only, showing what each button does while ringing and during a call.
- **"Android Auto has no picture" warning** when the connection opens but Android Auto sends no frame for 10 s
  (Android Auto's own encoder is stuck); the warning tells you to try again.
- **Terms of use**, in Portuguese and English, accepted on first launch, with the same copy in `TERMOS_DE_USO.md` (today
  `TERMS_OF_USE.pt-br.md`) and `TERMS_OF_USE.md`.
- The **log** now records the distance in Android Auto's position messages, to clarify rides with a route and no
  speed.

### Changed
- After a call is answered or ended, the same button goes quiet for 3 s, so an accidental repeated tap does not move
  Android Auto's focus; the pause applies only to the control that just acted.
- The Android Auto server restarts on the first failure after an install or a force stop, instead of waiting about
  33 s.

### Fixed
- A dash that responds but stopped sending the first-frame acknowledgment, after dropping in the middle of a
  call, was disconnected every 15 s.

### Security
- No account, ads or data collection. The only conversation with the internet is the weather forecast, which is
  optional and off by default.

## 2026-10-15

### Added
- **Real route on the dash** (behind a switch, off by default): time left, the turn after the next one and a no-signal
  icon.
- **Call volume by joystick**: up and down change the volume while a call is in progress.
- New **"Android Auto on the network"** screen, which gathers the network server, the browser and the sound; the home
  screen shows an icon that opens the data window, and a stop button. A "Debug" section in Settings, with the
  experimental options in red.
- The arrival time shows on the dash card, the maneuver ring bar now matches the navigation app's, and the estimated
  speed no longer spikes. The log now records the call state at each button press and the audio route.

### Changed
- **With the network server on, REau does not look for the dash**; the server turns on only after "Turn on" and turns
  off at the start and end of each projection. The browser server never starts by itself: it starts off in each
  projection.
- Video profiles stay at least 10 s at a level before switching, and the apps menu's own fit remembers what it was
  before. The time left now matches Waze's, and the estimated speed holds for 8 s, with no false speed when the
  kilometer count jumps.
- The texts of the (i) buttons and of two lines on the Android Auto screen are shorter, and "Image reduction" is now
  fixed at Default.

### Removed
- The "Slower Android Auto" and "Discard the start" options, and the "Image reduction" choice (see above).

### Fixed
- Accented characters in the song title and in the device name showed up wrong on the dash.
- The video profile switched every 10 s on the road, and choosing an item from the apps menu by joystick now
  returns to the apps fit right away.
- The dash recording, for diagnostics, now keeps up to 3000 frames.

## 2026-10-14

### Added
- **Network server** (optional, off by default): shows Android Auto on a page on the local network, with touch,
  protected by a 4-digit PIN with a lockout against guessing and a numeric keypad; it turns off by itself when the
  projection ends. There is a "Browser" section on the Android Auto screen.
- **Android Auto sound in the browser**, on the phone or on both, with a three-mode switch (Phone, Browser, Both); it
  includes Gemini's and Waze's voice. The sound goes to every connected browser, and a browser opened on the phone
  itself gets no sound (this avoids a capture loop). Calls can be answered from the browser by tapping the Android Auto
  card.
- **More complete log**: keyframe size, the downscale filter in use, the line with the settings in use, the reason the
  link ended, detailed video at one line per second and warning and error levels; no password goes into the log.
- **Bike data** now asks the dash when it connects and every 15 minutes.
- **Credits** redone, with REau's icon, libraries and fonts by author, and the weather service's credit also on the
  Weather screen.

### Changed
- **Android Auto protocol 1.7** is now the default (it brings the trip's total kilometers); the FPS and bitrate switch
  moves to the Android Auto screen.
- Settings polish: "Light/Dark" theme, "Remove the blue focus" now always applies, "Profile A" and "Profile B", and
  "Test card" becomes "Wallpaper" ("Test wallpaper", "Send wallpaper", "Back to default").
- Top bar with the same height on every screen; "REau and your data" and Permissions with shorter texts, ordered by
  importance. The log follows each event on a single line, with no repeated lines.

### Removed
- Some texts in Settings, and the list of call actions and the four arrows on the Joystick page.

### Fixed
- The contrast of the mask button in the light theme, and the browser showed a frozen image when nothing changed
  on the Android Auto screen (it now redraws).
- Turning on the sound switch no longer restarts Android Auto, a hiccup in the sound no longer drops the
  browser, and Android Auto's sound stopped being recaptured from REau itself in a loop.

## 2026-10-13

### Added
- **Bike data**: a read-only screen with what the dash reports (version, identification hidden until "Show", dash
  settings, ABS and fuel, codes that still have no name).
- **Weather on the dash** (optional, off by default): the forecast is fetched when REau opens, with 8 hours and 8 days,
  on its own screen with colored icons and hours in a list. With no weather data, the dash shows no made-up
  temperature.
- **Two fixed video profiles**: 4 fps at 400 kbps and 2 fps at 200 kbps, switched without rebuilding the encoder and
  holding the last level; a "Video profiles" group in Settings.
- Confirmation before opening Android's Accessibility page (which shows warnings from banking apps).
- Speed shown on the dash, a link quality report and reconnection of the dash's Wi-Fi.

### Changed
- Simpler dash Wi-Fi: name and password kept behind a single row.
- The Bike data screen is leaner: firmware shows only the time and state shows only "On or Off".
  "Maneuver and distance" is fixed and on.

### Removed
- The dash tests on the Bike data screen, and the fields for the start of the Wi-Fi name and for the password.

### Fixed
- A keyframe without the SPS and PPS parameters could be sent to the dash; it no longer is.
- Weather texts that wrapped badly or touched the edges, and the accessibility service is read again when it
  connects.

## 2026-10-11

### Added
- **FPS and bitrate text over the video** (switch under Advanced).
- **Quality profiles by navigation**: by maneuver, by speed, and the rule for which one wins.

### Removed
- The "motorcycle Wi-Fi" shortcut and the no-route bubble, for leaner Settings.

## 2026-10-10

### Added
- **New "Aurum" look**: new theme, fonts and icon, redesigned Log screen, notification and cover, light theme and
  **theme by the sun** (automatic by sunrise and sunset).
- **Dash Wi-Fi found and filled in by itself**, using location only to list networks; the name is hidden by default, and
  there is a warning when a VPN with split tunneling blocks the motorcycle's network.
- Home screen with the REau version, Wi-Fi on its own screen, a wallpaper chosen by name and no scrolling.
- When Android Auto's developer mode is off, the button takes you to Android Auto settings.
- Speed and Wi-Fi in the log's streaming line.

### Changed
- **Conservative video as the default**: 4 fps, 200 kbps, constant bitrate and a keyframe every 1 s, so the dash feels
  faster.
- The title on the dash is always "REau"; the notification no longer shows the app name.
- Android Auto server: the cover appears on touch, without the 2 s when turning off, and a stopped receiver does not
  wait before dialing again.

### Removed
- The "Nearby devices" permission, which had no use.

### Fixed
- On a clean install on Android 13 or newer, accessibility was restricted and the motorcycle's Wi-Fi did not
  receive the full name.

## 2026-10-09

### Added
- **Permissions pages** (with what each one does and buttons to request the missing ones), **"REau and your data"**
  (with a shortcut to turn off and delete the log, which can also be viewed inside the app) and **credits** for the
  projects and libraries used; the cover and the notice now say "Turning off".
- **Music card in the dash's format**, with album, title and artist, and the caller's name stays protected along with
  the call state.
- A "Battery low" warning once per level drop, with the level and the brand, and a frozen-dash warning that asks you to
  turn the motorcycle off; a drop requests the network only once.
- Android Auto's blue focus, left behind by a canceled tap, disappears one minute after the last joystick use.

### Changed
- **Privacy:** location is off by default; accessibility listens only to WhatsApp, Telegram and Signal, and only with
  the calls switch on; the log and recordings stay in the app's internal storage and are deleted by themselves after
  30 days. The dash's Wi-Fi name is masked on every log line.
- Night mode no longer depends on reading the position, which never arrived in the background.
- The maneuver ring is hidden in media mode; zoom and total distance icons appear only when they exist; the title beside
  the map names the app being shown.
- The Android Auto decoder now has its own thread, cutting the blocked read time from 81 ms to 3.6 ms per window in the
  tests.
- The new look begins: theme, components, main screen, Settings, Android Auto screen, Joystick, Permissions, data and
  Credits.

### Fixed
- The maneuver ring disappeared and did not come back in media mode.

## 2026-10-08

### Added
- **Music and caller on the dash's own cards**, from Android Auto's media and phone channels, with no new permission.
- **Calls by joystick also through Android Auto**, with the steering wheel keys, which makes WhatsApp calls work.
- The fit now follows the Android Auto screen even without a system notice.

### Changed
- The joystick is handled directly on receive, and Wi-Fi traffic gets network priority; the dash watchdog drops from 6
  to 4 s. Less processor and memory use in the log, in network receive and in packet reading.
- The log is more faithful: a requested end does not count as a drop, and unanswered authentication does not blame the
  Wi-Fi name.

### Fixed
- The maneuver ring disappeared on a stretch with no maneuver, because Waze sends "unknown" events every 2 to 3 s
  in the middle of a route.
- The menu fit got stuck on Waze on some screen changes.

## 2026-10-07

### Added
- **Android Auto apps menu detected** through accessibility, with its own fit switched automatically and an adjustable
  DPI on the Android Auto screen; the Google Maps viewer is also recognized.
- Calls: left answers and right declines on the call screen; voice-app calls are now read from what the notification
  carries.
- A request to exclude REau from battery optimization, with a warning when battery saver is on.
- Dash mask on the Android Auto screen, which can be turned on and off.
- Image crop beyond 0% and 100%, and then in pixels (1 by 1 and 10 by 10), until the whole window falls outside the
  image; the dome fit moves from Settings to the Android Auto screen.

### Changed
- The text of the calls switch speaks of a tap, not a hold.
- The end of an old projection no longer turns off the new one's server; when closing, REau waits for Android Auto's
  reply before ending.

### Fixed
- Waze's roundabout showed in the wrong direction; it is now counterclockwise for right-hand traffic.

## 2026-10-06

### Added
- **Android Auto server turned on and off by itself** by the accessibility service, with a cover over the screen during
  the procedure; the turn-on walkthrough takes 2.4 to 2.8 s.
- **Android Auto ping every 5 s**, which ends the 15 s "freeze".

### Changed
- Joystick: one tap, one action, and calls use the same sides.
- A burst of changes in Settings opens a single new Android Auto connection, 1 s after the last one.
- The "not responding" state appears after 3 unanswered handshakes; turning back on waits 1 s after turning off; the
  joystick button bounce is filtered.

### Removed
- The double tap on the joystick.

### Fixed
- In map mode with no route the dash stopped passing on the joystick sides, because REau hid the bubble; the
  no-route card now shows the arrival pin and right works.
- The log reader says "connected" only after the negotiation was answered.

## 2026-10-05

### Added
- **Gemini by joystick**: up twice in media mode, listening through the headset or the phone (behind a switch; asks for
  the microphone).
- **Joystick page in Settings**: map mode and media mode, with what to do on 1 tap, 2 taps and hold.
- The log's start line says which APK was running.

### Changed
- Video controls now apply with the projection on, without turning it back on.
- A missing dash is searched for every half minute for 5 min and then once a minute; the motorcycle network that the
  phone already connected to is now watched.

### Fixed
- Turning off and right back on took the projection out of the foreground, which blocked the network in the
  background and killed REau.

## 2026-10-04

### Added
- **Dash joystick wired to Android Auto**: wheel, arrows, confirm, back and home. In media mode, the wheel changes the
  track and the middle confirms; down opens the apps menu.
- **Calls by joystick** (new switch, with the answer-calls and phone-state permissions): answer or decline by the side
  of the call screen.
- **Android Auto screen on the phone** to see the projection, with a shortcut to Android Auto settings and an option to
  tap it from the phone's screen (behind a switch), also without the dash connected.
- **More complete log**: one file for as long as the app is alive, its own logcat, device and system lines, Android Auto
  and dash counts, a detailed log behind a switch, a "Mark" button on the notification, a summary of each projection,
  a 200 MB cap, sharing from the screen and a log reader for the computer.
- Motorcycle Wi-Fi joining by itself, without the choice window (switch); a Waze destination typed or chosen from the
  last three; diagnostic recordings: 50 kept, with names that do not collide, saved at every keyframe.

### Changed
- The dash is turned on only after Android Auto is up.
- The crop center and the new fit apply with the projection on; crop and position move 1 by 1.

### Fixed
- The dash Wi-Fi now uses the full network name (learned from the network list or with the app on screen),
  instead of the start of the name, which the dash refused.
- The "stopped" state in which the projection starts counted as a drop and opened two connection windows at every
  start; Wi-Fi failures are now reported for what is known.

## 2026-10-03

### Added
- **Wallpaper mode**: sends the dash a still image, without Android Auto.
- Coexistence with another dash app: REau notices when the motorcycle's Wi-Fi is busy.
- Day and night mode for Waze, and a frame cap that drops as the phone heats up.
- Android Auto's maneuver and distance in the dash bubble (behind a switch).
- The diagnostic log is opened at each projection, and an app crash is written before the process dies, along with the
  reason for the last process endings.
- **First video on the real dash**: authentication in under 0.1 s and a picture at 4 fps at about 160 kbps.

### Changed
- The picture fit was redone with the dash's real screen: a circle centered at (263, 263) with radius 263 inside the
  526 by 300 frame, with the trip computer below the video; there is an adjustment for the picture position.

## 2026-10-02

### Added
- **First version of the app**: Android project with a home screen, saved Settings, a foreground service and a
  projection state machine.
- **Android Auto receiver**: REau presents itself to Android Auto as a car's head unit, through local port 5277, with
  secure negotiation, service discovery, reception of H.264 video in chunks and reconnection.
- **Dash layer**: K1G packets, authentication, dash connection, motorcycle Wi-Fi with encrypted stored pairing, and
  video pacing.
- **Video to the dash**: screen geometry, encoder, compositor with its own clock and sending of RTP packets, with an
  option to record the sent video for debugging.
- Diagnostic log per projection, with a recording of the Android Auto connection and a replayer for tests.
- The whole path, from Android Auto to the dash, working inside the service (proven on an emulator).
- The project is now called **REau**.
