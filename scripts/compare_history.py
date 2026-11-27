"""AG-7 history comparison. See --help for the input and report formats."""

import argparse
from collections import defaultdict, deque
from contextlib import closing
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from email.utils import parsedate_to_datetime
import http.client
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
import tempfile
import time
from urllib.parse import urlencode
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


KEY_ENV = "CET_HISTORY_API_KEY"
PATH = "/sensor/api/history"
MAX_PERIOD = timedelta(days=7)
MAX_LIMIT = 5000
MAX_RETRIES = 3
COMPARISON = ("Linked collection Measurement.temperatureC versus API 5 temp at the exact UTC "
              "instant of feedback.collectedAt within [start, end). Difference is field minus history.")


class HistoryError(ValueError):
    pass


def utc_instant(value):
    if not isinstance(value, str):
        raise ValueError("Expected an ISO 8601 timestamp with a UTC offset.")
    instant = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if instant.tzinfo is None:
        raise ValueError("Expected an ISO 8601 timestamp with a UTC offset.")
    return instant.astimezone(timezone.utc)


def iso(instant):
    return instant.isoformat().replace("+00:00", "Z")


EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


def epoch_seconds(instant):
    delta = instant - EPOCH
    return Decimal(delta.days * 86400 + delta.seconds) + Decimal(delta.microseconds).scaleb(-6)


def exact_seconds(value):
    instant = utc_instant(value)
    fraction = re.search(r"T\d{2}:\d{2}:\d{2}[.,]([0-9]+)", value)
    seconds = epoch_seconds(instant)
    if fraction and len(fraction[1]) > 6:
        seconds += Decimal("0.000000" + fraction[1][6:])
    return seconds


def sensor_number(value):
    if type(value) is int:
        number = value
    elif isinstance(value, str) and re.fullmatch(r"(?:sensor )?[0-9]{1,2}", value):
        number = int(value.split()[-1])
    else:
        return None
    return number if 1 <= number <= 34 else None


def history_time(row):
    # Prefer the machine-readable timestamp. Do not replace a present malformed timestamp.
    stamp = row.get("timestamp")
    if stamp is not None:
        try:
            seconds = Decimal(str(stamp))
        except ArithmeticError:
            return exact_seconds(stamp)
        if not seconds.is_finite():
            raise ValueError("Invalid history timestamp.")
        return seconds
    raw = row.get("time")
    try:
        return exact_seconds(raw)
    except ValueError:
        if not isinstance(raw, str):
            raise
        # The human-readable form shared with §03, interpreted as Korea time.
        match = re.fullmatch(r"[A-Za-z]{3}\s+([A-Za-z]{3})\s+(\d{1,2})\s+(\d\d:\d\d:\d\d)\s+(\d{4})", raw)
        if not match:
            raise ValueError("Invalid history time.") from None
        month, day, clock, year = match.groups()
        months = "Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split()
        local = datetime.fromisoformat(f"{year}-{months.index(month) + 1:02d}-{int(day):02d}T{clock}")
        try:
            kst = ZoneInfo("Asia/Seoul")
        except ZoneInfoNotFoundError:
            kst = timezone(timedelta(hours=9))  # Modern KST on Windows without tzdata.
        return epoch_seconds(local.replace(tzinfo=kst).astimezone(timezone.utc))


def request_page(url_query, headers):
    connection = http.client.HTTPConnection("203.255.81.72", 10021, timeout=20)
    try:
        connection.request("GET", PATH + "?" + url_query, headers=headers)
        response = connection.getresponse()
        return response.status, response.getheader("Retry-After"), response.read()
    except (OSError, http.client.HTTPException, ValueError):
        raise HistoryError("API 5 connection failed. Check the network and key configuration.") from None
    finally:
        connection.close()


class HistoryClient:
    def __init__(self, key, request=request_page, clock=time.monotonic, sleep=time.sleep,
                 reserve=None):
        if not key or not key.isascii() or any(not 33 <= ord(ch) <= 126 for ch in key):
            raise HistoryError(f"Set {KEY_ENV} to a valid team key.")
        self._key = key
        self._request = request
        self._clock = clock
        self._sleep = sleep
        self._requests = deque()
        self._reserve = reserve if reserve is not None else self._reserve_request

    def _reserve_request(self):
        while True:
            now = self._clock()
            while self._requests and self._requests[0] <= now - 60:
                self._requests.popleft()
            if len(self._requests) < 30:
                self._requests.append(now)
                return
            self._sleep(self._requests[0] + 60 - now)

    def _page(self, query):
        for attempt in range(MAX_RETRIES + 1):
            self._reserve()
            status, retry_after, body = self._request(urlencode(query), {"X-API-Key": self._key})
            if status == 200:
                try:
                    page = json.loads(body, parse_float=Decimal)
                except (ValueError, UnicodeError):
                    raise HistoryError("API 5 returned invalid JSON.") from None
                if (not isinstance(page, dict) or not isinstance(page.get("rows"), list)
                        or len(page["rows"]) > query["limit"]):
                    raise HistoryError("API 5 returned an invalid history page.")
                return page
            if status == 400:
                raise HistoryError("API 5 rejected the query (400). Check sensor, start/end, mode and limit.")
            if status == 401:
                raise HistoryError(f"API 5 denied access (401). Check {KEY_ENV} and team-key permissions.")
            if status not in (429, 503) or attempt == MAX_RETRIES:
                raise HistoryError(f"API 5 request failed (HTTP {status}).")
            self._sleep(retry_delay(retry_after))

    def rows(self, sensor, start, end, limit=MAX_LIMIT, mode=None):
        if sensor_number(sensor) != sensor or type(sensor) is not int:
            raise HistoryError("Specify one sensor number from 1 through 34.")
        if not start < end <= start + MAX_PERIOD:
            raise HistoryError("The query period must be greater than zero and at most seven days.")
        if not 1 <= limit <= MAX_LIMIT:
            raise HistoryError("The page limit must be between 1 and 5000.")
        if mode not in (None, "direct", "mobile"):
            raise HistoryError("Mode must be direct or mobile.")
        query = {"sensor": f"sensor {sensor:02d}", "start": iso(start), "end": iso(end), "limit": limit}
        if mode is not None:
            query["mode"] = mode
        seen = set()
        while True:
            page = self._page(query)
            yield from page["rows"]
            cursor = page.get("next_cursor")
            if cursor is None or cursor == "":
                return
            if type(cursor) not in (str, int) or str(cursor) in seen:
                raise HistoryError("API 5 returned an invalid or repeated next_cursor.")
            seen.add(str(cursor))
            query["cursor"] = cursor


def reserve_local_request(database, clock=time.time, sleep=time.sleep):
    """Share a conservative request budget across CLI runs using the same temp directory."""
    while True:
        try:
            with closing(sqlite3.connect(database, isolation_level="IMMEDIATE")) as conn, conn:
                conn.execute("CREATE TABLE IF NOT EXISTS requests (sent_at REAL NOT NULL)")
                conn.execute("BEGIN IMMEDIATE")
                now = clock()
                conn.execute("DELETE FROM requests WHERE sent_at <= ?", (now - 60,))
                count, oldest = conn.execute("SELECT count(*), min(sent_at) FROM requests").fetchone()
                if count < 30:
                    conn.execute("INSERT INTO requests VALUES (?)", (now,))
                    return
        except sqlite3.Error:
            raise HistoryError("Could not reserve the local API 5 request budget.") from None
        sleep(max(0, oldest + 60 - clock()))


def retry_delay(header):
    if header:
        value = header.strip()
        if re.fullmatch(r"[0-9]+", value):
            return int(value)
        try:
            until = parsedate_to_datetime(value)
            if until.tzinfo is not None:
                return max(0, (until - datetime.now(timezone.utc)).total_seconds())
        except (ValueError, OverflowError):
            pass
    return 2


def valid_temperature(value):
    return type(value) in (int, float, Decimal) and -30 <= float(value) <= 50


def field_measurement(feedback, sensor, collected_seconds, start_seconds, end_seconds):
    # The export joins feedback.collection_id to collections.collection_id before writing this row.
    measurement = feedback.get("measurement")
    if not feedback.get("collectionId") or not isinstance(measurement, dict):
        return None, None, "NO_LINKED_FIELD_MEASUREMENT"
    measured_at = measurement.get("measuredAt")
    try:
        if (sensor_number(measurement.get("sensorNo")) != sensor
                or exact_seconds(measurement.get("collectedAt")) != collected_seconds):
            return None, None, "FIELD_MEASUREMENT_LINK_MISMATCH"
        if not start_seconds <= exact_seconds(measured_at) < end_seconds:
            return measured_at, None, "FIELD_MEASUREMENT_OUTSIDE_PERIOD"
    except (ValueError, OverflowError):
        return None, None, "INVALID_FIELD_MEASUREMENT_TIME"
    temp = measurement.get("temperatureC")
    if not valid_temperature(temp):
        return measured_at, None, "MISSING_OR_INVALID_FIELD_TEMPERATURE"
    return measured_at, float(temp), None


def compare(sensor, start, end, feedbacks, rows, mode=None):
    broken = sensor in (31, 32)
    start_seconds, end_seconds = epoch_seconds(start), epoch_seconds(end)
    temperatures = defaultdict(set)
    invalid_temperature_times = set()
    if not broken:
        for row in rows:
            if not isinstance(row, dict) or sensor_number(row.get("sensor")) != sensor:
                continue
            if mode is not None and row.get("mode") != mode:
                continue
            try:
                measured_at = history_time(row)
            except (ValueError, OverflowError, OSError):
                continue
            if not start_seconds <= measured_at < end_seconds:
                continue
            temp = row.get("temp")
            if valid_temperature(temp):
                temperatures[measured_at].add(float(temp))
            else:
                invalid_temperature_times.add(measured_at)

    entries = []
    for feedback in feedbacks:
        if not isinstance(feedback, dict):
            raise HistoryError("Each feedback must be a JSON object.")
        if sensor_number(feedback.get("sensorNo")) != sensor:
            continue
        try:
            collected_at = feedback.get("collectedAt")
            collected_seconds = exact_seconds(collected_at)
            raw_submitted_at = feedback.get("submittedAt")
            if raw_submitted_at is not None:
                utc_instant(raw_submitted_at)
        except (ValueError, OverflowError):
            raise HistoryError("Invalid feedback collectedAt or submittedAt. Use ISO 8601 with an offset.") from None
        if not start_seconds <= collected_seconds < end_seconds:
            continue
        values = temperatures.get(collected_seconds, ())
        entry = {
            "sensorNo": sensor,
            "feedbackId": feedback.get("feedbackId"),
            "collectionId": feedback.get("collectionId"),
            "collectedAt": collected_at,
            "submittedAt": raw_submitted_at,
            "fieldMeasuredAt": None,
            "fieldTemperatureC": None,
            "historyMeasuredAt": None,
            "historyTemperatureC": None,
            "differenceC": None,
            "result": "UNAVAILABLE",
        }
        if broken:
            entry["reason"] = "SENSOR_TEMPERATURE_UNAVAILABLE"
        else:
            field_time, field_temp, field_error = field_measurement(
                feedback, sensor, collected_seconds, start_seconds, end_seconds)
            entry.update(fieldMeasuredAt=field_time, fieldTemperatureC=field_temp)
            if len(values) == 1:
                historical_temp = next(iter(values))
                entry.update(historyMeasuredAt=collected_at, historyTemperatureC=historical_temp)
            if field_error:
                entry["reason"] = field_error
            elif not values and collected_seconds in invalid_temperature_times:
                entry["reason"] = "MISSING_OR_INVALID_TEMPERATURE_AT_EXACT_TIME"
            elif not values:
                entry["reason"] = "NO_VALID_EXACT_MATCH"
            elif len(values) != 1:
                entry["reason"] = "CONFLICTING_TEMPERATURES_AT_EXACT_TIME"
            else:
                difference = Decimal(str(field_temp)) - Decimal(str(historical_temp))
                entry.update(differenceC=float(difference), result="COMPARED")
        entries.append(entry)
    return {
        "sensorNo": sensor, "start": iso(start), "end": iso(end), "mode": mode,
        "comparison": COMPARISON, "entries": entries,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sensor", required=True, type=int, help="Sensor number 1-34.")
    parser.add_argument("--start", required=True, help="Start (inclusive), ISO 8601 with offset.")
    parser.add_argument("--end", help="End (exclusive), ISO 8601 with offset. Default: start + 7 days.")
    parser.add_argument("--mode", choices=("direct", "mobile"))
    parser.add_argument("--limit", type=int, default=MAX_LIMIT)
    parser.add_argument("--feedback", required=True, type=Path,
                        help="JSON array of feedback rows joined to collectionId and measurement "
                             "(sensorNo, measuredAt, collectedAt, temperatureC). submittedAt is optional.")
    args = parser.parse_args(argv)
    if sensor_number(args.sensor) is None or not 1 <= args.limit <= MAX_LIMIT:
        parser.error("Use a sensor from 1 through 34 and a page limit from 1 through 5000.")
    try:
        start = utc_instant(args.start)
        end = utc_instant(args.end) if args.end else start + MAX_PERIOD
        if (exact_seconds(args.start) != epoch_seconds(start)
                or (args.end and exact_seconds(args.end) != epoch_seconds(end))):
            raise HistoryError("Query dates support at most microsecond precision.")
        if not start < end <= start + MAX_PERIOD:
            raise HistoryError("The query period must be greater than zero and at most seven days.")
        with args.feedback.open(encoding="utf-8-sig") as source:
            feedbacks = json.load(source)
        if not isinstance(feedbacks, list):
            raise HistoryError("Feedback input must be a JSON array.")
        rows = ()
        if args.sensor not in (31, 32):
            database = Path(tempfile.gettempdir()) / "cet-history-rate.sqlite3"
            client = HistoryClient(os.environ.get(KEY_ENV),
                                   reserve=lambda: reserve_local_request(database))
            rows = client.rows(args.sensor, start, end, args.limit, args.mode)
        report = compare(args.sensor, start, end, feedbacks, rows, args.mode)
    except HistoryError as exc:
        parser.exit(1, f"{exc}\n")
    except (ValueError, OSError, OverflowError):
        parser.exit(1, "Check ISO 8601 dates and the feedback JSON file.\n")
    json.dump(report, sys.stdout, ensure_ascii=True, indent=2)
    print()


if __name__ == "__main__":
    main()
