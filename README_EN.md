# JM Reader

A native **Kotlin + Jetpack Compose** Android comic reader.

> [!IMPORTANT]
> **Disclaimer** — This is an **unofficial, third-party** client built for personal use and study.
> It is **not affiliated with, endorsed by, or connected to** 18comic / JMComic / 禁漫天堂.
> All comic content displayed by this app belongs to its respective copyright holders and is
> served by third-party sources. The app itself hosts or stores **no content**.
> Please respect copyright and the terms of service of any source you use this with.

[中文 Readme →](README.md)

## Features

- 📖 **Browse comics** — home (promote + latest), categories, weekly ranking, daily check-in,
  hot tags, keyword search, random recommendations
- 🔎 **Better search** — besides keyword search: **search by author** (results grouped per author)
  and **jump straight to a comic by its JM id**; cards show the author, the detail page links
  authors / tags to a search, and results page in as you scroll
- 📚 **Native reader** — vertical / horizontal paging, chapter switching, progress slider,
  automatic **image de-scrambling** (some album pages are served with reversed horizontal
  strips; this app restores them)
- 🌐 **Trilingual UI** — 简体中文 / 繁體中文 / English, switchable any time
- ⬇️ **One-tap download** — download a whole album (all chapters, all pages) as plain,
  de-scrambled JPEGs into `Downloads/JMReader/<albumId>/` on your device:
  visible in the Downloads folder, transferable via USB, readable by any gallery app,
  and playable in the built-in **offline reader** (no network needed)
- 🕘 **Browsing history** — every album you open is remembered on the device (no account
  needed, works offline), with per-item delete and clear-all
- 🛡️ **Crash report on next launch** — if the app crashes, the stack trace is saved and shown
  on the next launch with a one-tap **copy log** button
- 👤 **Member features** — login / register, favorites, viewing history, daily check-in, profile
- 🎬 **Secondary content** — novels, movies, games, blogs, forum

**No ads.** The entire advertising and recharge / coin-purchase layer is intentionally absent.

## Build

Requirements: JDK 17+, Android SDK with API 36.

```bash
# point gradle at your Android SDK
echo "sdk.dir=C:\\path\\to\\Android\\Sdk" > local.properties

# on Windows
gradlew.bat :app:assembleDebug

# on macOS / Linux
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Pushing a branch or opening a PR runs the same build on GitHub Actions and uploads the debug
APK as the `jm-reader-debug-apk` artifact (see `.github/workflows/android.yml`). CI only
produces debug builds; a release build still needs your own `jmreader.keystore`.

The release build is signed with a local `jmreader.keystore` (see `app/build.gradle.kts`);
generate your own keystore for distribution — the committed build script expects one.

## Project layout

```plaintext
app/src/main/kotlin/com/jm/reader/
├─ data/
│  ├─ net/        Crypto (md5/AES), ApiClient (signing + decryption), HostManager (bootstrap)
│  ├─ session/    SessionManager (jwt / memberInfo / host cache / language)
│  ├─ model/      JSON helpers + domain models
│  ├─ repo/       AppRepository (typed endpoint methods, no ad/coin methods)
│  └─ download/   DownloadManager (album download → Downloads/JMReader, offline index)
├─ ui/
│  ├─ strings/    AppStrings (zh-CN / zh-TW / en), UiLanguage, LanguageManager
│  └─ splash/ home/ detail/ reader/ download/ category/ search/
│     library/ member/ week/ daily/ novels/ movies/ games/ blogs/ forum/
└─ util/          ImageDescrambler, ReaderImageLoader (LRU cache), CrashHandler
```

## How it works (high level)

- **Host discovery** — on startup the app fetches an encrypted server list from a CDN,
  decrypts it, and picks a random API host.
- **Request signing** — every request carries `Tokenparam` / `Token` headers derived from
  the current time and a static key (matching the reference web client).
- **Response encryption** — API responses are AES-256-ECB encrypted; the app decrypts them
  before parsing.
- **Image de-scrambling** — the reader computes a deterministic slice count from
  `md5(albumId + pageName)` and re-assembles the reversed horizontal strips
  (`util/ImageDescrambler.kt`).

## License

[GNU General Public License v3.0](LICENSE) — see the `LICENSE` file.

Copyright © 2026 — contributions welcome.
