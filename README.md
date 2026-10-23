# Headswap — Android Auto on CarLife head units

Headswap runs **Android Auto** on head units that only support **Baidu CarLife 2.0** over USB — common on Chinese-market cars. The phone talks to Android Auto's "head unit server" on `127.0.0.1:5277` as if it were a head unit, re-encodes the video, and presents itself to the car as a CarLife phone over USB (AOA). Touch is mapped 1:1; audio and microphone go over USB. No dongles.

This is the **`mhzsajan/headswap` fork**, focused on connection reliability and an English-only interface. It is based on the upstream project by Yegor ([`YegorkaPomidorka/headswap`](https://github.com/YegorkaPomidorka/headswap)). Unofficial; not affiliated with Google, Baidu, or Honda. GPL-3.0.

![Android Auto on a factory Honda Vezel head unit](docs/screenshot.jpg)

## What this fork changes

Version **2.5.0-reliability2** (`versionCode 63`), on top of upstream **2.5.0**:

- **In-app connection diagnosis.** A "Why it won't connect?" screen reads the local log, lists likely causes with concrete actions, and records whether each step helped. The analysis runs on the phone; the log is not uploaded.
- **`tools/logdiag.py`.** The same rules can be run on a log file from a PC: `py tools/logdiag.py <log>` prints probable causes and advice.
- **Self-learning pause keepalive.** Some head units stop sending CarLife heartbeats while Android Auto is paused (for example during a phone call or a voice prompt). The bridge now learns, per head-unit channel and transport (`/usb` or `/air`), whether a pause needs its own keepalive. It learns after one failed pause, persists the result, and never learns this for Desay G6S over USB. No hardcoded per-car whitelist.
- **Upstream reliability fixes merged:**
  - VW CNS3 `20082130`: heartbeat on PAUSE.
  - VW CNS2 Delphi `20082114`: PAUSE heartbeat and VR `MIC_DATA` reassembly.
  - Wi-Fi Direct: better peer logging, reset, and loose peer matching.
  - P2P rejoin and Desay G6S air heartbeat.
  - Configurable music chunk size for USB audio.
- **`syncAaCerts` Gradle task.** Release builds verify that the head-unit certificates are present and copy them from the main worktree when needed, so a release cannot silently ship without them.
- **English-only UI.** The Russian locale and the in-app language picker were removed. Status text, notifications, onboarding, settings, the connection diagnosis, and the shared diagnostic report are English.

The bridge still accepts the same cars and head units as upstream. If a unit worked before, it should keep working; the new pause logic only adds a fallback.

## How it works

```
phone:  Android Auto  <->  Headswap  <->  USB (AOA, CarLife 2.0)  <->  head unit with CarLife
        (head unit server,   (bridge)
         127.0.0.1:5277)
```

- Headswap connects to Android Auto's head-unit server (AA developer mode) over TCP as a head unit: version, TLS, services, H.264 video.
- To the car it looks like a CarLife phone: it switches into USB accessory mode (AOA), completes the CarLife 2.0 handshake, and sends video on the VIDEO channel.
- Video is re-encoded and a frame is repeated every 100 ms. Without this, some head units drop USB on a static screen.
- Touches are 1:1: head-unit coordinates are converted to the video frame and sent to AA as touch events.
- AA audio and the car microphone go over USB (CarLife channels), not Bluetooth.
- The AA head-unit server is started automatically by an accessibility service: the app opens AA settings and taps the required menu item.

## Compatibility

Confirmed cars (factory head units with Baidu CarLife, all China-market):

| Car | Baidu CarLife |
| --- | --- |
| Dongfeng Peugeot 5008 2024 | — |
| Honda Breeze, 2nd generation | — |
| Honda CR-V, 6th generation, pre-facelift | — |
| Honda CR-V 2026 1.5T | 2.0 |
| Honda CR-V 2026 hybrid (works with caveats) | — |
| Honda Vezel 2022–2024 (GAC Honda) | 2.0 |
| Hyundai Elantra, 7th generation, pre-facelift | — |
| Hyundai ix35 2021 | — |
| Jetta VS5/VS7 (Baidu CarLife only; the J-Link version is not supported) | 2.0 |
| Kia K3 2023 | — |
| Kia Sportage Ace | — |
| Mazda CX-5 2025–2026 | 2.0 |
| Mazda CX-50 | — |
| Mitsubishi Outlander 4 2022 | — |
| Skoda Kamiq GT 2021 | — |
| Skoda Octavia Pro | 2.0 |
| Toyota Corolla Cross 2024–2025 | 2.0 |
| Toyota Levin | — |
| Volkswagen Golf 8, Pro trim, 2021 | — |
| Volkswagen ID.Unyx | — |
| Volkswagen Lamando L | — |
| Volkswagen Sagitar 2021–2022, pre-facelift | 2.0 |
| Volkswagen T-Roc facelift 2026 | — |
| Volkswagen Tharu XR 2025–2026 | 2.1 |
| Volkswagen Tiguan L Pro / Teramont Pro | — |

The upstream list is maintained at [yegorkapomidorka.com/headswap](https://yegorkapomidorka.com/headswap/). Reports for other cars are welcome. Minimum Android version: **10 (API 29)**.

## Install (users)

1. Download the APK from the [Releases page of this fork](https://github.com/mhzsajan/headswap/releases).
2. In Android Auto, enable developer mode (Settings → tap **Version** 10 times), then open the three-dot menu → **Start head unit server**.
3. In Android Settings → Accessibility, enable the **Headswap** service. It is needed to start the head-unit server automatically. If the item is greyed out: Settings → Apps → Headswap → **Allow restricted settings**.
4. Unlock the phone and connect it to the head unit over USB.

> This fork is signed with a different key than the upstream app. If upstream Headswap is already installed, uninstall it first; Android will not update across signatures. Uninstalling removes the app's saved settings.

Updates: Headswap checks the upstream GitHub releases and can install a newer upstream build over this one only if the signing key matches. For this fork, install a newer APK from this fork's Releases page.

## Build from source

JDK 17 or newer is required.

```bash
cd android
./gradlew assembleDebug
```

On Windows without a working Gradle wrapper download, use a locally installed Gradle:

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
cd android
gradle :app:assembleDebug
```

The release signing config is read from `~/.android-keys/headswap-release.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without that file only a debug build is produced. Never commit the keystore or its passwords.

Unit tests:

```bash
cd android
./gradlew :app:testDebugUnitTest
```

### Head unit certificate

The repository does not contain a head unit certificate. Android Auto only accepts a certificate signed by Google Automotive Link. Public certificate/key pairs used by open projects live in:

- [opencardev/aasdk](https://github.com/opencardev/aasdk) (continuation of [f1xpl/aasdk](https://github.com/f1xpl/aasdk)): JVC Kenwood, `src/Messenger/Cryptor.cpp` (lines 253–299), also `cert/headunit.crt` and `cert/headunit.key`;
- [andreknieriem/headunit-revived](https://github.com/andreknieriem/headunit-revived): `app/src/main/res/raw/cert` and `privkey`.

Place the PEM files here:

```text
android/app/src/main/assets/aa/jvc/cert.pem
android/app/src/main/assets/aa/jvc/key.pem
```

and/or (the headunit-revived set):

```text
android/app/src/main/assets/aa/hur/cert.pem
android/app/src/main/assets/aa/hur/key.pem
```

For the PC emulator, the same layout goes in `pc-headunit/certs/jvc/` (and `hur/`). `*.pem` and `*.key` are git-ignored.

## Repository layout

- `android/`: the app (Kotlin, package `com.yegorkapomidorka.headswap`): the AA ↔ CarLife bridge (`BridgeService`, `CarLifeSession`, `CarLifeState`, `AppLog`), the AA client (`aa/`), head-unit server automation (`autosrv/`), and the UI (`ui/`).
- `pc-headunit/`: a Python Android Auto head-unit emulator for debugging on a PC; see [pc-headunit/README.md](pc-headunit/README.md).
- `docs/`: protocol specifications: [aa-headunit.md](docs/aa-headunit.md) (Android Auto from the head-unit side), [carlife-protocol.md](docs/carlife-protocol.md) (CarLife over AOA), [carlife-v2-handshake.md](docs/carlife-v2-handshake.md) (CarLife 2.0 handshake).

## Limitations

- Calls work only over Bluetooth HFP; they do not go over USB.
- The phone must be unlocked for the head-unit server auto-start.
- HyperOS blocks `adb input`, so some debugging techniques are unavailable.
- CarLife 2.0 is implemented only as far as the confirmed head units require (no authentication or encryption). Units that require them will not work.
- Android Auto can change head-unit server behavior at any time: it is an undocumented developer mode.

## Links

- This fork: https://github.com/mhzsajan/headswap
- Upstream project and APK: https://yegorkapomidorka.com/headswap/
- Upstream releases: https://github.com/YegorkaPomidorka/headswap/releases
- Telegram: https://t.me/headswap
- 4PDA thread: https://4pda.to/forum/index.php?showtopic=1127415

## Disclaimer

Unofficial project. Not affiliated with Google, Baidu, or Honda; Android Auto, CarLife, and Honda are trademarks of their owners. Use at your own risk. Do not interact with the app while driving.

## License

GNU GPL v3.0, see [LICENSE](LICENSE). © 2026 Yegor (yegorkapomidorka). Fork changes © 2026 the fork contributors.

Exception: `pc-headunit/proto/*.proto` is based on protocol definitions from [headunit-revived](https://github.com/andreknieriem/headunit-revived) (AGPL-3.0). Everything else is GPL-3.0.
