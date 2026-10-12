import datetime as dt
import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from unittest import mock
from zoneinfo import ZoneInfo

import tagmod_server as ts

TZ = ZoneInfo("Europe/Madrid")
NOW = dt.datetime(2026, 10, 8, 12, 0, tzinfo=TZ)

ICS = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:test
BEGIN:VEVENT
UID:weekly
DTSTART;TZID=Europe/Madrid:20261001T180000
DTEND;TZID=Europe/Madrid:20261001T190000
RRULE:FREQ=WEEKLY
SUMMARY:Hackerspace
END:VEVENT
BEGIN:VEVENT
UID:cancelled
DTSTART;TZID=Europe/Madrid:20261009T100000
DTEND;TZID=Europe/Madrid:20261009T110000
STATUS:CANCELLED
SUMMARY:Cancelada
END:VEVENT
BEGIN:VEVENT
UID:allday
DTSTART;VALUE=DATE:20261010
DTEND;VALUE=DATE:20261011
SUMMARY:Fiesta
END:VEVENT
BEGIN:VEVENT
UID:past
DTSTART;TZID=Europe/Madrid:20261008T090000
DTEND;TZID=Europe/Madrid:20261008T100000
SUMMARY:Ya pasó
END:VEVENT
END:VCALENDAR
""".encode()


class CalendarTest(unittest.TestCase):
    def test_expands_recurrences_and_skips_cancelled_and_past(self):
        with mock.patch.object(ts, "fetch", return_value=ICS):
            events = ts.calendar_events([{"name": "Personal", "url": "x"}], TZ, NOW, 8)
        titles = [(e["title"], dt.datetime.fromtimestamp(e["start"], TZ).strftime("%m-%d %H:%M"), e["all_day"])
                  for e in events]
        self.assertEqual(titles, [
            ("Hackerspace", "10-08 18:00", False),
            ("Fiesta", "10-10 00:00", True),
            ("Hackerspace", "10-15 18:00", False),
        ])
        self.assertEqual(events[0]["calendar"], "Personal")


class TasksTest(unittest.TestCase):
    def test_markdown_checklist(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "tareas.md")
            with open(path, "w", encoding="utf-8") as f:
                f.write("# Tareas\n- [ ] Comprar pilas\n- [x] Hecha\n* [ ] 2026-10-09 Llamar al taller\ntexto\n")
            tasks = ts.sort_tasks(ts.markdown_tasks(path, TZ))
        self.assertEqual([t["title"] for t in tasks], ["Llamar al taller", "Comprar pilas"])
        self.assertEqual(dt.datetime.fromtimestamp(tasks[0]["due"], TZ).date(), dt.date(2026, 10, 9))
        self.assertIsNone(tasks[1]["due"])


class FeedTest(unittest.TestCase):
    def test_failing_source_keeps_last_good_value(self):
        feed = ts.Feed({"calendar": [{"url": "x"}]})
        with mock.patch.object(ts, "fetch", return_value=ICS):
            first = feed.build()["events"]
        feed.cache["calendar"] = (0, first)  # expire it
        with mock.patch.object(ts, "fetch", side_effect=OSError("down")):
            self.assertEqual(feed.build()["events"], first)

    def test_http_requires_token(self):
        feed = ts.Feed({})
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), ts.make_handler(feed, "s3cret-token-1234"))
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        url = f"http://127.0.0.1:{httpd.server_port}/feed.json"
        try:
            with self.assertRaises(urllib.error.HTTPError) as e:
                urllib.request.urlopen(url)
            e.exception.close()
            self.assertEqual(e.exception.code, 401)
            req = urllib.request.Request(url, headers={"Authorization": "Bearer s3cret-token-1234"})
            with urllib.request.urlopen(req) as r:
                self.assertEqual(json.load(r)["version"], 1)
        finally:
            httpd.shutdown()
            httpd.server_close()


if __name__ == "__main__":
    unittest.main()
