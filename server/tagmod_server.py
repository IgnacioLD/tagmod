#!/usr/bin/env python3
"""tagmod-server: the data backend for the TagMod e-reader panel.

Serves GET /feed.json (Bearer token) with upcoming calendar events and open tasks, in the format
documented in app/src/org/tagmod/launcher/Feed.java. The e-reader renders it (and monitors the
services itself).

    tagmod-server --config config.toml
"""
import argparse
import datetime as dt
import hmac
import json
import os
import re
import ssl
import sys
import threading
import time
import tomllib
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from zoneinfo import ZoneInfo

import certifi
import icalendar
import recurring_ical_events

FEED_VERSION = 1
CACHE_SECONDS = 300
HTTP_TIMEOUT = 10
MAX_EVENTS = 20
MAX_TASKS = 12
# Mozilla's CA bundle, so HTTPS fetches do not depend on the host's certificate store.
TLS = ssl.create_default_context(cafile=certifi.where())


def fetch(url, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": "tagmod-server", **(headers or {})})
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT, context=TLS) as r:
        return r.read()


def epoch(value, tz):
    """iCalendar date/datetime -> epoch seconds. All-day dates are midnight in tz."""
    if isinstance(value, dt.datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=tz)
        return int(value.timestamp())
    return int(dt.datetime.combine(value, dt.time(), tz).timestamp())


# Calendar.

def calendar_events(calendars, tz, now, days):
    start, end = now, now + dt.timedelta(days=days)
    events = []
    for cal in calendars:
        parsed = icalendar.Calendar.from_ical(fetch(cal["url"]))
        for ev in recurring_ical_events.of(parsed).between(start, end):
            if str(ev.get("STATUS", "")).upper() == "CANCELLED":
                continue
            dtstart = ev.get("DTSTART").dt
            dtend = ev.get("DTEND").dt if ev.get("DTEND") else dtstart
            all_day = not isinstance(dtstart, dt.datetime)
            ends = epoch(dtend, tz)
            if ends <= now.timestamp():
                continue
            events.append({
                "start": epoch(dtstart, tz),
                "end": ends,
                "all_day": all_day,
                "title": str(ev.get("SUMMARY", "(sin título)")),
                "calendar": cal.get("name", ""),
            })
    events.sort(key=lambda e: (e["start"], not e["all_day"]))
    return events[:MAX_EVENTS]


# Tasks.

TASK_LINE = re.compile(r"^\s*[-*]\s+\[ \]\s+(?:(\d{4}-\d{2}-\d{2})\s+)?(.+?)\s*$")


def markdown_tasks(path, tz):
    """Open checklist items ("- [ ] text", optionally "- [ ] 2026-10-09 text") from a Markdown file."""
    tasks = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = TASK_LINE.match(line)
            if m:
                due = epoch(dt.date.fromisoformat(m.group(1)), tz) if m.group(1) else None
                tasks.append({"title": m.group(2), "due": due, "source": "markdown"})
    return tasks


def todoist_tasks(token, filter_, tz):
    url = "https://api.todoist.com/rest/v2/tasks?filter=" + urllib.parse.quote(filter_)
    tasks = []
    for t in json.loads(fetch(url, {"Authorization": "Bearer " + token})):
        due = None
        if t.get("due"):
            due = epoch(dt.date.fromisoformat(t["due"]["date"][:10]), tz)
        tasks.append({"title": t["content"], "due": due, "source": "todoist"})
    return tasks


def sort_tasks(tasks):
    # Dated tasks first, soonest first; undated ones keep their order.
    return sorted(tasks, key=lambda t: (t["due"] is None, t["due"] or 0))[:MAX_TASKS]


# Feed with per-source caching and error isolation.

class Feed:
    def __init__(self, config):
        self.config = config
        self.tz = ZoneInfo(config.get("timezone", "Europe/Madrid"))
        self.lock = threading.Lock()
        self.cache = {}

    def _cached(self, key, compute):
        hit = self.cache.get(key)
        if hit and time.time() - hit[0] < CACHE_SECONDS:
            return hit[1]
        try:
            value = compute()
        except Exception as e:
            print(f"tagmod-server: {key}: {e}", file=sys.stderr)
            # Serve the last good value rather than an empty section.
            return hit[1] if hit else []
        self.cache[key] = (time.time(), value)
        return value

    def build(self):
        c, tz = self.config, self.tz
        now = dt.datetime.now(tz)
        with self.lock:
            events = self._cached("calendar", lambda: calendar_events(
                c.get("calendar", []), tz, now, c.get("calendar_days", 7)))
            tasks_cfg = c.get("tasks", {})
            tasks = []
            if "file" in tasks_cfg:
                tasks += self._cached("tasks_file", lambda: markdown_tasks(tasks_cfg["file"], tz))
            if "todoist_token" in tasks_cfg:
                tasks += self._cached("todoist", lambda: todoist_tasks(
                    tasks_cfg["todoist_token"], tasks_cfg.get("todoist_filter", "today | overdue"), tz))
        return {
            "version": FEED_VERSION,
            "generated": int(now.timestamp()),
            "events": events,
            "tasks": sort_tasks(tasks),
        }


def make_handler(feed, token):
    class Handler(BaseHTTPRequestHandler):
        server_version = "tagmod-server"

        def do_GET(self):
            if self.path == "/health":
                return self.reply(200, b"ok\n", "text/plain")
            if self.path != "/feed.json":
                return self.reply(404, b"not found\n", "text/plain")
            given = self.headers.get("Authorization", "")
            if not hmac.compare_digest(given.encode(), ("Bearer " + token).encode()):
                return self.reply(401, b"unauthorized\n", "text/plain")
            body = json.dumps(feed.build(), ensure_ascii=False).encode()
            self.reply(200, body, "application/json; charset=utf-8")

        def reply(self, code, body, ctype):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt, *args):
            print("tagmod-server: " + fmt % args, file=sys.stderr)

    return Handler


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--config", default=os.environ.get("TAGMOD_CONFIG", "config.toml"))
    ap.add_argument("--print", action="store_true", help="print the feed once and exit")
    args = ap.parse_args()
    with open(args.config, "rb") as f:
        config = tomllib.load(f)
    feed = Feed(config)
    if args.print:
        print(json.dumps(feed.build(), ensure_ascii=False, indent=2))
        return
    token = config["token"]
    if len(token) < 16:
        sys.exit("token must be at least 16 characters")
    host, port = config.get("listen", "127.0.0.1:8765").rsplit(":", 1)
    httpd = ThreadingHTTPServer((host, int(port)), make_handler(feed, token))
    print(f"tagmod-server listening on {host}:{port}", file=sys.stderr)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
