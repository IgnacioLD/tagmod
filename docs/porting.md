# Porting TagMod to other e-readers

TagMod is split into independent pieces. Most can be adapted to any Android-based e-ink device.

## What you need for each device

### powerkeyd (C daemon)

Find the input devices on your reader:

```sh
adb shell getevent -i
```

Look for the power button (usually `gpio-keys` or a PMIC like `axp20x-pek`) and the keyboard
input you want to inject into (usually `rk29-keypad` or `gpio-keys`). Update the paths in
`src/powerkeyd.c`:

```c
static const char *in_path  = "/dev/input/event0";  // your power button
static const char *out_path = "/dev/input/event2";  // your keyboard emulation
static const char *pwr_path = "/dev/input/event1";  // power passthrough
```

KitKat-era Rockchip devices (RK3026, RK3128, RK3188) usually share the same layout.
Newer devices (Android 6+) may need minor adjustments to the autosleep paths:

```sh
# Check how your device reports screen state
cat /sys/power/autosleep   # "mem"=sleep, "off"=awake
cat /sys/power/state        # alternative path on some kernels
```

Compile for your architecture:

```sh
# ARMv7 (most old readers)
arm-linux-gnueabihf-gcc -mcpu=cortex-a7 -Os -static -s -o powerkeyd src/powerkeyd.c

# AArch64 (newer readers like Onyx Boox Palma)
aarch64-linux-gnu-gcc -Os -static -s -o powerkeyd src/powerkeyd.c
```

### Launcher APK

The APK targets API 19 (KitKat). It works on any Android >= 4.4 with minimal changes.

- **Resolution**: `PanelRenderer.W`/`H` is currently 758x1024 (Tagus Gaia). Adjust for your
  screen in `app/src/org/tagmod/launcher/PanelRenderer.java`.
- **Frontlight**: `Frontlight.java` writes to `/sys/class/backlight/{white,warm}/brightness`.
  Check your device's paths with `ls /sys/class/backlight/` or `/sys/class/leds/`.
- **Root CAs**: The bundled Mozilla CA bundle in `res/raw/` covers modern HTTPS. If your
  device uses Android 7+, you can remove `Http.java`'s custom TrustManager.

### Boot image

The `bootimg/` scripts handle standard Android v0 boot images (most Rockchip and
Mediatek devices). NXP/Freescale uses a different format.

- Extract your stock boot image: `python3 bootimg/bootimg.py unpack stock-boot.img`
- Add the TagMod ramdisk files to the extracted ramdisk
- Update `init.rc` import and `default.prop` as `build-boot.py` does
- Repack: `python3 bootimg/bootimg.py repack stock-boot-mod.img`

### Resource partition (bootloader logo)

Rockchip-specific. Other SoCs (Mediatek, Allwinner, NXP) have different partition layouts.
Skip this piece if your device doesn't have a `resource` partition.

## Device-specific notes

### Kindle (Fire OS / Android-based)

- Fire tablets run Fire OS, a heavily modified Android. Root access is harder to obtain.
- The launcher APK should work but you'll need a different boot unlock method.
- For e-ink Kindles (non-Android), this whole stack won't work directly. Consider a
  Raspberry Pi Zero running the server + a Python framebuffer dashboard instead.

### Kobo e-readers

Kobo runs Linux, not Android. To adapt:
- The Python `tagmod-server` piece works unchanged
- For the dashboard: use the framebuffer directly with Python/PIL, polling the server
- For button control: Kobo has native nickel hooks or you can use `evtest`/`input-utils`

### Onyx Boox (newer models)

- All run Android 6+, which is easier: no custom TLS code needed, `AlarmManager` works
  from suspend (no CLOCK_REALTIME_ALARM workaround needed)
- Most use AArch64: recompile powerkeyd with `aarch64-linux-gnu-gcc`
- Newer Boox devices have both touch AND physical buttons. You might not need powerkeyd.

### Generic Android e-ink tablet

- Check `/sys/class/graphics/fb0/epd_*` or vendor-specific EPD ioctls for screen refresh
- Some devices need explicit `MODE_UPDATE` ioctls after drawing (Hisense, Boyue)
- Most Rockchip-based e-readers (Boyue Likebook, Icarus, Energy Sistem) share similar
  partitions to the Tagus

## Minimum viable port

The simplest port is just the dashboard:
1. Install the APK on any rooted Android e-ink reader
2. Set `feed_url` to your `tagmod-server` (or a `file://` JSON for offline use)
3. Use `adb shell am startservice -n org.tagmod.launcher/.PanelService` to render
4. The sleep screen will update on `PANEL_UPDATE` intents or scheduled alarms