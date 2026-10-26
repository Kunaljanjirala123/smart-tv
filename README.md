# Glass Home

A fast, ad-free home screen for Fire TV in a liquid-glass style.

- **Liquid glass:** frosted panels that show the colours behind them, a bright rim and sheen,
  and one glass highlight that springs from app to app.
- **Your apps first:** a dock of up to five favourites on the first screen. Press Down for every
  other app.
- **Controls at the top:** Inputs (HDMI), network status, Settings and Edit.
- **No ads, nothing downloaded:** the app has no internet permission, so it cannot show ads,
  load recommendations or send data anywhere. Its only permission is reading whether the TV is
  on Wi-Fi or Ethernet.
- **Smooth:** the highlight and the page glide on springs that keep their speed when you hold a
  D-pad key, the page moves in sub-pixel steps, tiles lift with real shadows drawn by the GPU,
  and nothing is allocated while anything moves, so there are no garbage-collection stutters.
- **Quick:** apps open with no transition, the wallpaper is drawn once in code, and the
  installer compiles your apps fully so they start faster.

## Remote

| Button | What it does |
|---|---|
| D-pad | Move. Down from the dock goes to all apps; Up goes to the controls. |
| OK | Open the app. Hold OK for its menu. |
| Menu (three lines) | On an app: Add to dock, Move, Hide, App info. Elsewhere: Edit. |
| Back | Return to the first dock app. |
| Home | Glass Home. |

**Edit** (top right) has *Show a hidden app*, *Reset layout* and *Open Amazon's home screen*.

## Install from a Windows PC

1. Turn on ADB debugging on the TV: Settings > My Fire TV > About > press OK on the name 7 times,
   then Developer Options > ADB debugging.
2. Download `glass-home.apk` and `glass-home.apk.sha256` from the
   [latest release](../../releases/latest) into the `windows` folder with the scripts.
3. Double-click `windows/Install-GlassHome.bat`, type the TV's IPv4 address, press Allow on the TV.
   If adb is already connected to the TV it is used without asking, or give the address on the
   command line: `Install-GlassHome.bat 10.0.0.5`.
4. At the end it offers to clean the TV (below).
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
(`.github/workflows/build.yml`) and publishes the APK as a release. Locally:
`ANDROID_HOME=... bash app/build.sh` (JDK 17, `platforms;android-30`, `build-tools;34.0.0`).

The signing key in `signing/` is committed so every build can update the installed app. Anyone
with this repository can sign an APK with it, but installing one on your TV still needs ADB
access to it.

## Licence

MIT. `Guard.java` and `Input.java` are adapted from dumbtv home (MIT).
