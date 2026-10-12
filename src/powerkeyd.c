/*
 * powerkeyd - drive a Tagus/Onyx e-reader with only the power button.
 *
 * Grabs the power key input device exclusively (so Android never sees it)
 * and turns press patterns into key events written to other input devices:
 *
 *   tap            -> next      (KEY_PAGEDOWN)
 *   double tap     -> previous  (KEY_PAGEUP)
 *   hold 0.8-3s    -> back      (KEY_BACK, sent on release)
 *   hold >= 3s     -> sleep     (KEY_POWER on the passthrough device, sent while held)
 *
 * While the screen is off any press only wakes the device.
 *
 * It also listens on the abstract unix datagram socket "@tagmod" for commands from the TagMod
 * launcher:
 *
 *   refresh        -> if the screen is off, wake and sleep again so the e-ink panel redraws
 *                     the standby image (the TagMod panel), keeping the frontlight off.
 *   awake <secs>   -> hold a kernel wakelock for up to <secs> seconds (Onyx's framework refuses
 *                     app wakelocks while the screen is off).
 *   release        -> drop that wakelock.
 *   schedule <t>   -> at Unix time t, wake the system (kernel alarm timer, so it fires from
 *                     suspend) and start the launcher's panel update. Replaces any earlier one.
 *                     Android alarms cannot do this: Onyx's framework drops them while asleep.
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <signal.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <sys/timerfd.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define DOUBLE_MS 300
#define LONG_MS 800
#define SLEEP_MS 3000

static const char *in_path = "/dev/input/event0";   /* axp20x-pek */
static const char *out_path = "/dev/input/event2";  /* rk29-keypad */
static const char *pwr_path = "/dev/input/event1";  /* axp22x-hall */
static int verbose;

static void logmsg(const char *fmt, ...)
{
	va_list ap;
	if (!verbose)
		return;
	va_start(ap, fmt);
	vfprintf(stderr, fmt, ap);
	va_end(ap);
	fputc('\n', stderr);
}

static long long now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int emit(int fd, int type, int code, int value)
{
	struct input_event ev;
	memset(&ev, 0, sizeof(ev));
	ev.type = type;
	ev.code = code;
	ev.value = value;
	return write(fd, &ev, sizeof(ev)) == sizeof(ev) ? 0 : -1;
}

static void press(const char *path, int code)
{
	int fd = open(path, O_WRONLY);
	if (fd < 0) {
		fprintf(stderr, "powerkeyd: open %s: %s\n", path, strerror(errno));
		return;
	}
	if (emit(fd, EV_KEY, code, 1) || emit(fd, EV_SYN, SYN_REPORT, 0) ||
	    emit(fd, EV_KEY, code, 0) || emit(fd, EV_SYN, SYN_REPORT, 0))
		fprintf(stderr, "powerkeyd: write %s: %s\n", path, strerror(errno));
	close(fd);
}

/* Android sets autosleep to "mem" when it turns the screen off and "off" when it turns it on. */
static int screen_on(void)
{
	char buf[8] = "";
	int fd = open("/sys/power/autosleep", O_RDONLY);
	if (fd < 0)
		return 1;
	ssize_t n = read(fd, buf, sizeof(buf) - 1);
	close(fd);
	return !(n >= 3 && !strncmp(buf, "mem", 3));
}

static void write_str(const char *path, const char *value)
{
	int fd = open(path, O_WRONLY);
	if (fd < 0)
		return;
	if (write(fd, value, strlen(value)) < 0)
		logmsg("write %s: %s", path, strerror(errno));
	close(fd);
}

static void lights_off(void)
{
	write_str("/sys/class/backlight/white/brightness", "0");
	write_str("/sys/class/backlight/warm/brightness", "0");
}

/* Waits up to timeout_ms for the screen to reach the wanted state, keeping the frontlight off. */
static int wait_screen(int on, int timeout_ms)
{
	long long end = now_ms() + timeout_ms;
	while (now_ms() < end) {
		lights_off();
		if (screen_on() == on)
			return 1;
		usleep(50 * 1000);
	}
	return 0;
}

/*
 * The standby image is drawn by surfaceflinger when the display blanks, so a wake/sleep cycle
 * puts a freshly written standby image on the panel. Android restores the frontlight on wake;
 * keep it off for the whole cycle so a refresh never lights up the room. A kernel wakelock stops
 * autosleep from suspending between the steps.
 */
static void refresh_panel(void)
{
	if (screen_on()) {
		logmsg("refresh: screen is on, skipped");
		return;
	}
	logmsg("refresh");
	write_str("/sys/power/wake_lock", "tagmod");
	press(pwr_path, KEY_POWER);
	if (wait_screen(1, 5000)) {
		/* Give the frontlight restore and the first frame time to happen, still dark. */
		long long end = now_ms() + 1500;
		while (now_ms() < end) {
			lights_off();
			usleep(50 * 1000);
		}
		press(pwr_path, KEY_POWER);
		wait_screen(0, 5000);
	} else {
		logmsg("refresh: device did not wake");
	}
	write_str("/sys/power/wake_unlock", "tagmod");
}

#ifndef CLOCK_REALTIME_ALARM
#define CLOCK_REALTIME_ALARM 8
#endif

static int alarm_fd = -1;

static void schedule_update(long when)
{
	struct itimerspec its;
	memset(&its, 0, sizeof(its));
	its.it_value.tv_sec = when;
	if (alarm_fd < 0 || timerfd_settime(alarm_fd, TFD_TIMER_ABSTIME, &its, NULL) < 0)
		fprintf(stderr, "powerkeyd: schedule: %s\n", strerror(errno));
	else
		logmsg("panel update scheduled at %ld", when);
}

/* The alarm woke us (possibly from suspend): stay up and let the launcher update the panel. */
static void run_scheduled_update(void)
{
	uint64_t expirations;
	if (read(alarm_fd, &expirations, sizeof(expirations)) < 0)
		return;
	logmsg("scheduled panel update");
	write_str("/sys/power/wake_lock", "tagmod_panel 120000000000");
	pid_t pid = fork();
	if (pid == 0) {
		execl("/system/bin/am", "am", "startservice", "-n", "org.tagmod.launcher/.PanelService",
		      "-a", "org.tagmod.launcher.PANEL_UPDATE", "--ez", "show", "true", (char *)NULL);
		_exit(127);
	}
	if (pid < 0)
		fprintf(stderr, "powerkeyd: fork: %s\n", strerror(errno));
}

static int open_command_socket(void)
{
	struct sockaddr_un addr;
	memset(&addr, 0, sizeof(addr));
	addr.sun_family = AF_UNIX;
	/* Abstract namespace: sun_path starts with a NUL byte. */
	memcpy(addr.sun_path + 1, "tagmod", 6);
	int fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
	if (fd < 0 || bind(fd, (struct sockaddr *)&addr, offsetof(struct sockaddr_un, sun_path) + 7) < 0) {
		fprintf(stderr, "powerkeyd: command socket: %s\n", strerror(errno));
		if (fd >= 0)
			close(fd);
		return -1;
	}
	return fd;
}

static void handle_command(int sock)
{
	char buf[64];
	ssize_t n = recv(sock, buf, sizeof(buf) - 1, 0);
	if (n <= 0)
		return;
	buf[n] = 0;
	buf[strcspn(buf, "\r\n")] = 0;
	int secs;
	long when;
	if (!strcmp(buf, "refresh")) {
		refresh_panel();
	} else if (sscanf(buf, "awake %d", &secs) == 1 && secs > 0 && secs <= 600) {
		/* "name timeout_ns": the kernel drops it by itself if "release" never comes. */
		char lock[64];
		snprintf(lock, sizeof(lock), "tagmod_panel %lld", secs * 1000000000LL);
		write_str("/sys/power/wake_lock", lock);
	} else if (!strcmp(buf, "release")) {
		write_str("/sys/power/wake_unlock", "tagmod_panel");
	} else if (sscanf(buf, "schedule %ld", &when) == 1 && when > 0) {
		schedule_update(when);
	} else
		logmsg("unknown command '%s'", buf);
}

static void act_tap(void)    { logmsg("tap -> PAGEDOWN"); press(out_path, KEY_PAGEDOWN); }
static void act_double(void) { logmsg("double -> PAGEUP"); press(out_path, KEY_PAGEUP); }
static void act_long(void)   { logmsg("long -> BACK"); press(out_path, KEY_BACK); }
static void act_sleep(void)  { logmsg("sleep -> POWER"); press(pwr_path, KEY_POWER); }
static void act_wake(void)   { logmsg("wake -> POWER"); press(pwr_path, KEY_POWER); }

enum state { IDLE, HELD, WAIT_SECOND };

int main(int argc, char **argv)
{
	int opt;
	while ((opt = getopt(argc, argv, "i:o:p:v")) != -1) {
		switch (opt) {
		case 'i': in_path = optarg; break;
		case 'o': out_path = optarg; break;
		case 'p': pwr_path = optarg; break;
		case 'v': verbose = 1; break;
		default:
			fprintf(stderr, "usage: %s [-v] [-i in] [-o out] [-p power]\n", argv[0]);
			return 2;
		}
	}
	signal(SIGPIPE, SIG_IGN);
	/* Children (am) are reaped automatically. */
	signal(SIGCHLD, SIG_IGN);

	int fd = open(in_path, O_RDONLY);
	if (fd < 0) {
		fprintf(stderr, "powerkeyd: open %s: %s\n", in_path, strerror(errno));
		return 1;
	}
	if (ioctl(fd, EVIOCGRAB, 1) < 0) {
		fprintf(stderr, "powerkeyd: grab %s: %s\n", in_path, strerror(errno));
		return 1;
	}
	logmsg("grabbed %s", in_path);

	/* EPOLLWAKEUP keeps the system awake until the event is handled (needs CAP_BLOCK_SUSPEND). */
	int ep = epoll_create1(EPOLL_CLOEXEC);
	struct epoll_event eev = { .events = EPOLLIN | EPOLLWAKEUP, .data.fd = fd };
	if (ep < 0 || epoll_ctl(ep, EPOLL_CTL_ADD, fd, &eev) < 0) {
		fprintf(stderr, "powerkeyd: epoll: %s\n", strerror(errno));
		return 1;
	}
	/* Scheduled panel updates; EPOLLWAKEUP keeps the system up until the expiry is handled. */
	alarm_fd = timerfd_create(CLOCK_REALTIME_ALARM, TFD_CLOEXEC);
	struct epoll_event aev = { .events = EPOLLIN | EPOLLWAKEUP, .data.fd = alarm_fd };
	if (alarm_fd < 0 || epoll_ctl(ep, EPOLL_CTL_ADD, alarm_fd, &aev) < 0) {
		fprintf(stderr, "powerkeyd: alarm timer: %s\n", strerror(errno));
		if (alarm_fd >= 0)
			close(alarm_fd);
		alarm_fd = -1;
	}

	/* The button keeps working without the command socket. */
	int sock = open_command_socket();
	struct epoll_event sev = { .events = EPOLLIN, .data.fd = sock };
	if (sock >= 0 && epoll_ctl(ep, EPOLL_CTL_ADD, sock, &sev) < 0) {
		fprintf(stderr, "powerkeyd: epoll socket: %s\n", strerror(errno));
		close(sock);
		sock = -1;
	}

	enum state st = IDLE;
	int presses = 0, slept = 0, waking = 0;
	long long t_down = 0, t_up = 0;

	for (;;) {
		int timeout = -1;
		long long t = now_ms();
		if (st == HELD && presses == 1 && !waking && !slept) {
			timeout = t_down + SLEEP_MS > t ? (int)(t_down + SLEEP_MS - t) : 0;
		} else if (st == WAIT_SECOND) {
			timeout = t_up + DOUBLE_MS > t ? (int)(t_up + DOUBLE_MS - t) : 0;
		}

		int r = epoll_wait(ep, &eev, 1, timeout);
		if (r < 0) {
			if (errno == EINTR)
				continue;
			perror("powerkeyd: epoll_wait");
			return 1;
		}
		t = now_ms();

		if (r > 0 && eev.data.fd == sock) {
			handle_command(sock);
			continue;
		}
		if (r > 0 && eev.data.fd == alarm_fd) {
			run_scheduled_update();
			continue;
		}

		if (r == 0) {
			if (st == WAIT_SECOND) {
				act_tap();
				st = IDLE;
			} else if (st == HELD) {
				/* Still held at SLEEP_MS: sleep now, the release is ignored. */
				act_sleep();
				slept = 1;
			}
			continue;
		}

		struct input_event ev;
		ssize_t n = read(fd, &ev, sizeof(ev));
		if (n != sizeof(ev)) {
			if (n < 0 && errno == EINTR)
				continue;
			fprintf(stderr, "powerkeyd: read: %s\n", n < 0 ? strerror(errno) : "short");
			return 1;
		}
		if (ev.type != EV_KEY || ev.code != KEY_POWER || ev.value == 2)
			continue;

		if (ev.value == 1) {
			if (!screen_on()) {
				act_wake();
				waking = 1;
				st = HELD;
				continue;
			}
			presses = st == WAIT_SECOND ? 2 : 1;
			slept = 0;
			t_down = t;
			st = HELD;
		} else if (st == HELD) {
			if (waking || slept) {
				waking = 0;
				st = IDLE;
			} else if (presses == 2) {
				act_double();
				st = IDLE;
			} else if (t - t_down >= LONG_MS) {
				act_long();
				st = IDLE;
			} else {
				t_up = t;
				st = WAIT_SECOND;
			}
		}
	}
}
