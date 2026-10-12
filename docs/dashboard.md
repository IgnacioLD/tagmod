# Using TagMod as an e-ink dashboard

TagMod turns your e-reader's sleep screen into a [TRMNL](https://usetrmnl.com)-style dashboard
that refreshes on schedule while the device remains in deep suspend.

## What the dashboard shows

- **Header**: weekday, date, battery level and charging status
- **Agenda**: upcoming calendar events (from `tagmod-server` or a `file://` JSON)
- **Tasks**: pending tasks with due dates (Markdown checklist or Todoist)
- **Current book**: last-opened book from the device
- **Analytics**: Umami stats: visitors, sessions, pageviews, avg. time, 24h comparison,
  7-day chart, top custom events
- **Services**: uptime monitor with 24h bars and 7-day percentages, incident list
- **Weather**: current conditions + hourly + 7-day forecast (shown when nothing else is configured)

## Setup checklist

### 1. Server (optional but recommended)

Deploy `tagmod-server` on a VPS or Raspberry Pi:

```sh
cd server
cp config.example.toml config.toml
# Add your iCal URLs, tasks source, and a random token
```

Docker Compose example:

```yaml
services:
  tagmod:
    build: ./server
    volumes:
      - ./config:/config
      - ./data:/data
    ports:
      - "8765:8765"
    restart: unless-stopped
```

Put it behind Nginx/Caddy with HTTPS.

### 2. Device configuration

Create `/sdcard/TagMod/panel.txt` with your settings:

```
feed_url=https://tagmod.example.com/feed.json
feed_token=your-long-random-token
```

For Umami analytics on the dashboard:

```
umami_url=https://umami.example.com
umami_key=your-umami-api-key
umami_web=your-tracked-domain.com
```

For uptime monitoring, create `/sdcard/TagMod/servicios.txt`:

```
My Website = https://example.com
API = https://api.example.com/health
Database = tcp://db.example.com:5432
```

The launcher creates a template file if it doesn't exist.

### 3. Update interval

In the launcher, select "Panel refresh" (default 60 minutes). The daemon schedules a
`CLOCK_REALTIME_ALARM` kernel timer that wakes the device from deep suspend, triggers a
`PanelService` update, and goes back to sleep immediately. The frontlight stays off so
the room stays dark.

### 4. View the panel

Put the device to sleep (hold the power button 3+ seconds). The dashboard appears as the
sleep screen. It refreshes automatically on schedule.

You can also trigger an immediate refresh from the launcher ("Ver panel" row) or via adb:

```sh
adb shell am startservice -n org.tagmod.launcher/.PanelService \
  -a org.tagmod.launcher.PANEL_UPDATE --ez show true
```

## Offline mode

If you don't have a server, you can use local JSON files:

```sh
# Create a feed.json on the device
adb push my-feed.json /sdcard/TagMod/feed.json
```

Then set `feed_url=file:///sdcard/TagMod/feed.json` in `panel.txt`. See `out/demo/feed.json`
for the format.

Without any feed or analytics configuration, the dashboard falls back to a full weather
forecast (uses free Open-Meteo API, no key needed).

## Adding custom panels

`PanelRenderer.java` is a plain Canvas drawing loop. To add your own data:
1. Collect it in `PanelSource.collect()`
2. Add fields to `PanelRenderer.Data`
3. Add a render method in `PanelRenderer.render()`

Examples of common additions:
- Crypto/stock ticker (fetch from an API)
- RSS headline feed
- Public transport departures
- GitHub contribution graph
- Home Assistant sensor data

## Layout tips for e-ink

- E-ink has ~200 ms refresh time. Rendering is instant; keep it that way.
- Use pure black and white. Grays wash out or ghost. The dashboard already does this.
- Avoid frequency-based dithering (it causes moire on e-ink).
- The panel is rendered at 758x1024 then rotated -90 degrees (surfaceflinger orientation).
  If your device has a different panel orientation, adjust accordingly.