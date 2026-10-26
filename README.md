# Glass Home

A fast, ad-free home screen for Fire TV, in the style of tvOS, built from liquid glass.

- **The tvOS look:** a light theme with frosted glass (the default), Dark, the original night-sky
  Aurora, or Auto: light from sunrise to sunset and dark at night.
- **The stage:** the focused app's banner glows large in the middle of the screen, and the
  backdrop takes on its colour.
- **tvOS app icons:** each app's own icon on its brand colour, with a glass shine; TV banners
  are a setting away. A focused icon lifts with a real shadow and a band of light sweeps across it.
- **Weather:** conditions and temperature at the top left, and the hourly and 5-day forecast
  on OK.
- **Profile:** a name and an animal avatar next to the clock.
- **Control Center** (OK on the clock): Wi-Fi, Bluetooth, inputs, appearance, StandBy, free
  memory, settings and the apps you used last.
- **Folders and jiggle mode:** put apps in folders; pick an app up, move it with the arrows,
  put it down with OK.
- **StandBy:** a big clock with the date, a calendar and the weather on black, as the TV's
  screensaver or from Control Center. It drifts a little every minute so nothing burns in.
- **No ads, no tracking:** Glass Home shows nothing it was paid to show and sends nothing
  about you anywhere. Its only use of the internet is the weather, from
  [Open-Meteo](https://open-meteo.com) (open data, no account, no key), and only for the city
  you choose. Turn the weather off and it uses the internet for nothing at all.
- **Smooth:** the page and the highlight glide on springs that keep their speed when you hold a
  D-pad key, changes crossfade on the GPU, and nothing is allocated while anything moves, so
  there are no garbage-collection stutters.
- **Quick:** apps open with no transition, the wallpaper is drawn once in code, and the
  installer compiles your apps fully so they start faster.

## Remote

| Button | What it does |
|---|---|
| D-pad | Move. Down from the dock goes to every app; Up goes to the weather and the clock. |
| OK | Open the app. On the weather: the forecast. On the clock: Control Center. |
| Menu (three lines) | On an app: Move, Dock, Move to Folder, Hide, Close App, App Info. Elsewhere: Settings. |
| Back | Return to the first dock app. |
| Home | Glass Home. |

**Settings** (the gear, top right) has the look, icon style, focus effect, stage, dock size, apps
per row, status bar, weather, profile, StandBy, hidden apps, *Run Setup Again*, *Reset Layout*
and *Open Amazon's Home Screen*.

## Install from a Windows PC

1. Turn on ADB debugging on the TV: Settings > My Fire TV > About > press OK on the name 7 times,
   then Developer Options > ADB debugging.
2. Download `glass-home.apk` and `glass-home.apk.sha256` from the
   [latest release](../../releases/latest) into the `windows` folder with the scripts.
3. Double-click `windows/Install-GlassHome.bat`, type the TV's IPv4 address, press Allow on the TV.
   If adb is already connected to the TV it is used without asking, or give the address on the
   command line: `Install-GlassHome.bat 10.0.0.5`.
4. It asks for your city (for the weather) and whether StandBy should be the screensaver, then
   offers to clean the TV (below). The first time Glass Home opens, a short setup lets you
   choose the look and your profile.
5. Turn ADB debugging off again when it finishes.

`windows/Uninstall-GlassHome.bat` gives HOME back to Amazon and removes the app.

## Removing Amazon's adverts and junk

`windows/Clean-FireTV.bat` (also offered at the end of the install) keeps the TV working and:

- switches off Amazon's advert apps (sponsored screensavers, deals, Freevee), its tracking and
  crash uploads, Wi-Fi password sync, GameCircle and the help videos;
- optionally turns on ad-blocking encrypted DNS (`dns.adguard-dns.com`) for every app, and puts
  the old DNS straight back if the TV cannot look names up through it;
- optionally stops automatic Fire OS updates, which tend to switch adverts back on;
- halves the app open/close animation time and leaves in-app animations at full smoothness.

Nothing is deleted. It never touches Amazon's home screen, settings, the Appstore, HDMI and live
TV inputs, the keyboard, Alexa, the TV maker's system apps or anything you installed. Every
change is listed in `windows/cleaned.txt`, and `windows/Restore-FireTV.bat` undoes all of it.
Adverts that are part of a video stream (YouTube, for example) cannot be blocked this way.

## How HOME is taken over

Fire OS ignores the "default home app" setting for third-party apps. Like
[dumbtv](https://github.com/humblemuzzu/dumbtv), Glass Home uses a small accessibility
service (`Guard.java`) that notices Amazon's home screen and replaces it before it is drawn. It
reads no screen content. The installer also sets `min_futurity=0` for alarms so the switch is
immediate.

## Building

GitHub Actions builds every push to `main` with Google's official Android SDK
(`.github/workflows/build.yml`) and publishes the APK as a release. Pull requests are built too,
walked through on an Android TV emulator (`.github/preview.sh`), and their screenshots and APK
published as a `preview-pr-N` pre-release, so a change can be seen and tried before it is merged.
Locally:
`ANDROID_HOME=... bash app/build.sh` (JDK 17, `platforms;android-30`, `build-tools;34.0.0`).

The signing key in `signing/` is committed so every build can update the installed app. Anyone
with this repository can sign an APK with it, but installing one on your TV still needs ADB
access to it.

## Licence

MIT. `Guard.java` and `Input.java` are adapted from dumbtv home (MIT). The look and features
follow the tvOS Launcher by Siddhant; no code or images from it are used.
