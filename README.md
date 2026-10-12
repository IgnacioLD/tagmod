# TagMod

Turn an old e-ink Android e-reader into a one-button Kindle and a [TRMNL](https://usetrmnl.com)-style dashboard.

Drives everything with the power button (for readers with a broken touchscreen) and shows
agenda, tasks, analytics, uptime monitor and weather on the sleep screen, refreshed on a schedule
while the device stays suspended.

Built for Onyx Tagus (Rockchip RK312x, Android 4.4.4), but the pieces are independent and most
can be adapted to other e-ink devices running Android.

| Gesture | Library | Reading |
|---|---|---|
| Tap | next item | next page |
| Double tap | previous item | previous page |
| Hold 0.8-3 s, release | open / activate | back to library |
| Hold 3 s | sleep (shows the panel) | sleep |
| Any press while asleep | wake | wake |

## Architecture

```
powerkeyd (C, root daemon)
  grabs power key input, emits synthetic key events
  scheduled panel updates from deep suspend (CLOCK_REALTIME_ALARM)
  listens on @tagmod abstract socket for launcher commands

launcher APK (Java, API 19, no Gradle)
  one-button library with frontlight control
  PanelService/PanelRenderer: 758x1024 e-ink dashboard
  StatusTracker: uptime monitor for your servers
  Umami: self-hosted analytics client
  Http: TLS 1.2 + bundled root CAs for Android 4.4

tagmod-server (Python)
  optional backend: ICS calendars + Markdown/Todoist tasks -> feed.json
```

## Build

```sh
# Cross-compile the C daemon (needs Docker)
./build.sh

# Build and sign the launcher APK (needs Android SDK build-tools)
# Set TAGMOD_KEYSTORE_PASS for a custom signing key password
./build-apk.sh

# Build the bootloader logo and sleep art (needs ImageMagick, rsvg-convert)
art/build-art.sh
```

## Install

```sh
# First flash (Loader mode): backs up stock partitions, flashes TagMod boot image
adb reboot bootloader
deploy/flash.sh backup  stock
deploy/flash.sh install stock

# Later updates (root adb): rebuilds and overwrites boot + resource partitions
deploy/update.sh stock

# Add a wifi network
deploy/wifi.sh <ssid> <password>

# Restore to stock
deploy/flash.sh restore stock
```

## Panel settings (on the e-reader at `/sdcard/TagMod/`)

**`panel.txt`** — key=value, one per line:
```
feed_url=https://your-tagmod-server/feed.json
feed_token=your-bearer-token
umami_url=https://your-umami
umami_key=your-api-key
umami_web=your-site-domain
ubicacion=City Name
```

Use a `file:///sdcard/TagMod/feed.json` URL for offline demo data.

**`servicios.txt`** — one service per line:
```
Web = https://example.com
API = https://api.example.com/health
Private = https://private.example.com 401
```

## Server (optional)

```sh
cp server/config.example.toml server/config.toml
# Edit config.toml with your calendars, tasks, and token
docker build -t tagmod-server server/
docker run -v ./config:/config -v ./data:/data -p 8765:8765 tagmod-server
```

Put it behind HTTPS and set `feed_url`/`feed_token` on the device.

## Guides

- [Porting to other e-readers](docs/porting.md) — Kindle, Kobo, generic Android e-ink
- [Using as an e-ink dashboard](docs/dashboard.md) — TRMNL-like monitor setup
- [Firmware modding](docs/firmware.md) — boot image, resource partition, ramdisk

## License

MIT — see [LICENSE](LICENSE).