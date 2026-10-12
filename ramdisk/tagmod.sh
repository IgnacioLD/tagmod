#!/system/bin/sh
# TagMod boot service (init.tagmod.rc), running as root from the boot ramdisk.
BB=/system/bin/busybox
T=/tagmod

# Power button first, so the device is usable as soon as possible.
# The loop restarts powerkeyd if it ever exits.
(
	while :; do
		$T/powerkeyd
		log -t tagmod "powerkeyd exited with $?, restarting"
		sleep 2
	done
) &

# Sleep / shutdown / boot art: surfaceflinger and bootanimation prefer /data/local over the stock copies.
install_file() {
	if ! $BB cmp -s "$1" "$2"; then
		$BB cp "$1" "$2.tmp" && $BB chmod 644 "$2.tmp" && $BB mv "$2.tmp" "$2"
	fi
}
# The sleep image (standby-N.png, N picked at random) belongs to the TagMod panel: standby-2..6
# link to standby-1, which the launcher app writes. The stock art is kept in assets/art for when
# the panel is off.
A=/data/local/assets
$BB mkdir -p $A/images $A/art
$BB chmod 755 $A $A/images $A/art
install_file $T/art/images/standby-1.png $A/art/standby.png
for f in shutdown reboot; do
	install_file $T/art/images/$f.png $A/images/$f.png
done
[ -f $A/images/standby-1.png ] || install_file $A/art/standby.png $A/images/standby-1.png
for i in 2 3 4 5 6; do
	[ -L $A/images/standby-$i.png ] || { $BB rm -f $A/images/standby-$i.png; $BB ln -s standby-1.png $A/images/standby-$i.png; }
done
install_file $T/art/bootanimation.zip /data/local/bootanimation.zip

# Package manager tasks need the package service. Do not wait for sys.boot_completed: Android
# only sets it once a home screen is up, and with two homes it waits on the chooser forever.
while ! pm path android 2>/dev/null | $BB grep -q package:; do
	sleep 1
done

# Launcher: (re)install when the APK in the ramdisk differs from the last one installed.
STAMP=/data/local/tagmod-launcher.sha1
sum=$($BB sha1sum $T/launcher.apk | $BB cut -d' ' -f1)
if [ "$(cat $STAMP 2>/dev/null)" != "$sum" ] || [ -z "$(pm path org.tagmod.launcher)" ]; then
	# The package manager cannot read /tagmod (root only); hand it a world-readable copy.
	APK=/data/local/tmp/tagmod-launcher.apk
	$BB cp $T/launcher.apk $APK && $BB chmod 644 $APK
	if pm install -r $APK | $BB grep -q Success; then
		echo "$sum" > $STAMP
	else
		log -t tagmod "launcher install failed"
	fi
	$BB rm -f $APK
fi

# Make TagMod the only home screen, but only once it is installed so there is always a home.
if [ -n "$(pm path org.tagmod.launcher)" ]; then
	for c in HomeActivity StartupActivity; do
		pm disable com.onyx/com.onyx.content.browser.activity.$c >/dev/null
	done
	# The Onyx status bar only has touch buttons; TagMod and the reader show battery and time
	# themselves. Takes effect from the next boot.
	pm disable com.android.systemui >/dev/null
	# The Casa del Libro store is gone and pops a sign-up form whenever wifi connects. The OTA
	# updaters are what bricked these devices (a corrupt update.zip); keep them off for good.
	for p in com.onyx.android.bookstore com.onyx.android.onyxotaservice android.rockchip.update.service; do
		pm disable $p >/dev/null
	done

	# Let the launcher write the panel image (this busybox has no stat applet).
	uid=$($BB ls -nd /data/data/org.tagmod.launcher | $BB awk '{print $3}')
	$BB chown $uid:$uid $A/images $A/images/standby-1.png
fi

wait
