# Firmware modding guide

How TagMod modifies the device without touching `/system`, and how to adapt and extend it.

## Tagus partition layout (Rockchip RK312x)

Each partition is an entry in the parameter file. TagMod touches only two:

| Partition | Block start | Size (blocks) | Purpose |
|---|---|---|---|
| `boot` | 0x16000 | 0x10000 | Kernel + ramdisk (8 MiB limit) |
| `resource` | 0x6000 | 0x8000 | Bootloader logo (uboot_logo.bmp) |

Everything else (system, vendor, recovery, uboot) is left untouched.

## Boot image structure

Android v0 boot image:

```
+------------------+
| header (1 page)  |
+------------------+
| kernel (zImage)  |
+------------------+
| ramdisk (gzip)   |
+------------------+
| second (unused)  |
+------------------+
| dtb (optional)   |
+------------------+
```

TagMod adds files to the ramdisk and patches `init.rc` to run a service before Android
starts. The kernel is stock, untouched.

### What gets added to the ramdisk

- `/tagmod/powerkeyd` — the C daemon
- `/tagmod/tagmod.sh` — init script (restarts daemon, deploys art/APK, disables Onyx bloat)
- `/tagmod/launcher.apk` — the launcher APK (installed on every boot if changed)
- `/tagmod/art/` — standby, shutdown, reboot images
- `/tagmod/init.tagmod.rc` — Android init service definition

### init.rc patches

`build-boot.py` adds to the stock `init.rc`:

```rc
import /tagmod/init.tagmod.rc
```

And to `default.prop`:

```
ro.secure=0
ro.adb.secure=0
ro.debuggable=1
```

### What init.tagmod.rc does

```rc
service tagmod /system/bin/sh /tagmod/tagmod.sh
    user root
    group root
    seclabel u:r:init:s0
    oneshot
```

### What tagmod.sh does

1. Kills any lingering powerkeyd, restarts it in a loop
2. Copies sleep/shutdown/boot art to `/data/local/assets/` (where surfaceflinger looks)
3. Installs `launcher.apk` if SHA1 differs from the installed version
4. Disables Onyx launcher, status bar, bookstore and OTA services
5. Chowns art files to the launcher UID

## Resource partition (bootloader logo)

Rockchip's `resource` partition contains bitmap images for the bootloader screen.
`uboot_logo.bmp` is at a fixed offset and must have the exact same size as stock
(failure to match size bricks the bootloader logo, not the boot).

`build-resource.py` replaces it in-place by reading the stock image, checking the
offset via BITMAPINFOHEADER search, and writing the new BMP data at the same position.

To create a custom bootloader logo:

```sh
# Create a 275x75 grayscale BMP (Tagus portrait, stored rotated)
magick -size 275x75 xc:white ... -rotate -90 -colorspace Gray gray:logo.gray
python3 art/make-uboot-logo.py logo.gray uboot_logo.bmp
python3 bootimg/build-resource.py stock-resource.img uboot_logo.bmp resource-tagmod.img
```

## Flashing (Rockchip Loader mode)

Rockchip devices use RKUSB/Loader mode, not fastboot. `rkdeveloptool` is the flashing
tool (open-source, from rockchip-linux).

```
adb reboot bootloader    -> enters Loader mode (maskrom on cold boot)
rkdeveloptool rd 3       -> reset
rkdeveloptool wl <addr> <file>  -> write LBA
rkdeveloptool rl <addr> <sectors> <file>  -> read LBA
```

The `deploy/flash.sh` script handles the addresses and resync logic.

## Safety

### Always back up first

```sh
deploy/flash.sh backup stock
```

This reads stock boot + resource partitions into `backups/stock/`. Keep them.

### Recovery

If the boot image is broken:
1. Enter Loader mode (hold power + some key combo, varies by device)
2. Flash the stock backup: `deploy/flash.sh restore stock`

If the bootloader itself is bricked, you need `maskrom` mode (short eMMC clock pin to
ground during boot). This is device-specific and carries risk.

### What NOT to touch

- `uboot` / `trust` — bootloader partitions. Brick = paperweight.
- `vendor` — contains hardware-specific blobs and calibration data.
- `parameter` — partition table. Wrong offsets = all partitions inaccessible.
- `misc` — recovery command. Corrupted = boot loop to recovery.

## Extending the firmware mod

### Adding more boot art

Standby images are symlinked. `standby-2` through `standby-6` are all copies of the
chess-king standby art. You can replace them with different images and the device
will pick one randomly (depending on surfaceflinger's selection logic).

### Adding a custom boot animation

Replace `out/art/bootanimation.zip` with your own. Format:

```
desc.txt:
<width> <height> <fps>
p 0 0 part0

part0/a01.png  (uncompressed/stored PNG entries)
part0/a02.png
...
```

The default animation is the "tagmod" wordmark with 12 dots filling in.

### Removing Onyx services

`tagmod.sh` already disables:
- `com.onyx.android.launcher` — stock launcher
- `com.onyx.android.systemui` — status bar
- `com.onyx.android.bookstore` — Onyx bookstore
- `com.onyx.android.onyxotaservice` — OTA updates
- `android.rockchip.update.service` — Rockchip OTA

To disable more, add `pm disable <package>` commands to `tagmod.sh`.

### Over-the-air updates

TagMod disables OTA. If you ever want to update the stock firmware:
1. Flash stock boot + resource: `deploy/flash.sh restore stock`
2. Boot normally, apply OTA
3. Re-flash TagMod: `deploy/flash.sh install stock`
4. Restore `/sdcard/TagMod/` settings from backup