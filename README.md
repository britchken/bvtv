# B and V's TV!!

A goofy 1990s-homepage homescreen for Android TV (built for a Hisense 65U7G).

- Joke of the day in the scrolling banner (from icanhazdadjoke.com, with built-in jokes when offline)
- Jump back in, Up next, Recently used and Faves rows: recents fill themselves in; hold OK on any app to pin or unpin it
- HDMI input buttons, TV Album (opens Synology Photos), real TV settings shortcuts
- WordArt style switcher in Settings: rainbow, fire, chrome, ocean

## Get the APK

Every push to `main` builds it automatically. The latest is always at:

https://github.com/britchken/bvtv/releases/latest/download/RetroTV.apk

## Undo

- Uninstall like any app: Settings > Apps > B and V's TV > Uninstall
- If you made it the Home screen with ADB, bring the original back with:
  `adb shell pm enable com.google.android.tvlauncher`

Fonts: Luckiest Guy, Comic Neue, VT323, Press Start 2P (SIL Open Font License, see `app/src/main/assets/fonts`).
