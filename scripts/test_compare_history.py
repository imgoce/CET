"""Offline unittest coverage for the AG-7 script."""

from datetime import timedelta
import json
from pathlib import Path
import tempfile
import unittest
from urllib.parse import parse_qs

from compare_history import HistoryClient, HistoryError, compare, reserve_local_request, utc_instant


START = utc_instant("2026-11-20T00:00:00Z")
END = START + timedelta(days=7)
COLLECTED = "2026-11-20T09:00:00+09:00"
SUBMITTED = "2026-11-20T00:05:00Z"


def page(rows=(), cursor=None):
    return 200, None, json.dumps({"rows": list(rows), "next_cursor": cursor})


def row(sensor="sensor 07", when=COLLECTED, temp=20, mode="mobile"):
    return {"sensor": sensor, "timestamp": when, "temp": temp, "mode": mode}


def feedback(sensor=7, collected=COLLECTED, submitted=SUBMITTED):
    return {"sensorNo": sensor, "collectedAt": collected, "submittedAt": submitted,
            "collectionId": "10000000-0000-4000-8000-000000000003",
            "measurement": {"sensorNo": sensor, "collectedAt": collected,
                            "measuredAt": collected, "temperatureC": 24.5}}


class FakeTransport:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.now = 0
        self.calls = []
        self.sleeps = []

    def request(self, query, headers):
        self.calls.append((self.now, parse_qs(query), headers))
        return next(self.responses)

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds

    def client(self):
        return HistoryClient("mock-team-key", self.request, lambda: self.now, self.sleep)


class HistoryTest(unittest.TestCase):
    def test_pages_preserve_query_and_send_key_only_in_header(self):
        transport = FakeTransport([page([row()], "a+/="), page([row(temp=24)])])
        self.assertEqual(2, len(list(transport.client().rows(7, START, END, 1, "mobile"))))
        _, first, headers = transport.calls[0]
        _, second, _ = transport.calls[1]
        self.assertEqual({"X-API-Key": "mock-team-key"}, headers)
        self.assertEqual({"sensor": ["sensor 07"], "start": ["2026-11-20T00:00:00Z"],
                          "end": ["2026-11-27T00:00:00Z"], "limit": ["1"],
                          "mode": ["mobile"]}, first)
        self.assertEqual(dict(first, cursor=["a+/="]), second)

    def test_limit_is_shared_by_pages_and_retries(self):
        responses = [page(cursor=str(i)) for i in range(29)]
        responses += [(429, "3", b""), page()]
        transport = FakeTransport(responses)
        list(transport.client().rows(7, START, END))
        self.assertEqual([3, 57], transport.sleeps)
        self.assertEqual([0] * 30 + [60], [call[0] for call in transport.calls])
        self.assertEqual(transport.calls[-2][1], transport.calls[-1][1])

    def test_retry_after_and_bounded_retry_count(self):
        transport = FakeTransport([(429, "4", b""), (503, "7", b""), page()])
        list(transport.client().rows(7, START, END))
        self.assertEqual([4, 7], transport.sleeps)
        failing = FakeTransport([(503, "1", b"")] * 4)
        with self.assertRaisesRegex(HistoryError, "503"):
            list(failing.client().rows(7, START, END))
        self.assertEqual(4, len(failing.calls))
        self.assertEqual([1, 1, 1], failing.sleeps)

    def test_local_budget_is_shared_across_clients(self):
        transport = FakeTransport([page(cursor=str(i)) for i in range(29)] + [page(), page()])
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / "budget.sqlite3"
            reserve = lambda: reserve_local_request(database, lambda: transport.now, transport.sleep)
            first = HistoryClient("mock-team-key", transport.request, reserve=reserve)
            second = HistoryClient("mock-team-key", transport.request, reserve=reserve)
            list(first.rows(7, START, END))
            list(second.rows(8, START, END))
        self.assertEqual([60], transport.sleeps)

    def test_errors_are_actionable_and_do_not_include_key_or_body(self):
        for status, hint in [(400, "Check sensor"), (401, "CET_HISTORY_API_KEY")]:
            transport = FakeTransport([(status, None, b"mock-team-key")])
            with self.assertRaisesRegex(HistoryError, hint) as caught:
                list(transport.client().rows(7, START, END))
            self.assertNotIn("mock-team-key", str(caught.exception))
            self.assertEqual(1, len(transport.calls))

    def test_rejects_oversized_requests_and_repeated_cursor(self):
        transport = FakeTransport([])
        client = transport.client()
        with self.assertRaisesRegex(HistoryError, "seven days"):
            list(client.rows(7, START, END + timedelta(seconds=1)))
        with self.assertRaisesRegex(HistoryError, "5000"):
            list(client.rows(7, START, END, 5001))
        with self.assertRaisesRegex(HistoryError, "one sensor"):
            list(client.rows("sensor 07,sensor 08", START, END))
        self.assertFalse(transport.calls)
        repeated = FakeTransport([page(cursor="a"), page(cursor="a")])
        with self.assertRaisesRegex(HistoryError, "repeated"):
            list(repeated.client().rows(7, START, END))

    def test_compares_linked_collection_time_and_filters_sensor_period_and_mode(self):
        rows = [row(temp=0), row("sensor 08", temp=49), row(when=SUBMITTED, temp=15),
                row(when="2026-11-19T23:59:59Z", temp=25),
                row(when="2026-11-27T00:00:00Z", temp=30), row(mode="direct", temp=35)]
        report = compare(7, START, END, [feedback(), feedback(8),
                                       feedback(collected="2026-11-27T00:00:00Z")], rows, "mobile")
        self.assertEqual(1, len(report["entries"]))
        entry = report["entries"][0]
        self.assertEqual("COMPARED", entry["result"])
        self.assertEqual(24.5, entry["fieldTemperatureC"])
        self.assertEqual(0, entry["historyTemperatureC"])
        self.assertEqual(24.5, entry["differenceC"])
        self.assertEqual(COLLECTED, entry["historyMeasuredAt"])
        self.assertEqual(SUBMITTED, entry["submittedAt"])
        self.assertEqual("UNAVAILABLE", compare(7, START, END, [feedback()], rows[1:], "mobile")
                         ["entries"][0]["result"])

    def test_missing_invalid_faulty_and_ambiguous_temperatures(self):
        for sensor in (31, 32):
            report = compare(sensor, START, END, [feedback(sensor)], [row(sensor)])
            self.assertEqual("SENSOR_TEMPERATURE_UNAVAILABLE", report["entries"][0]["reason"])
        missing = compare(7, START, END, [feedback()], [row(temp=None), row(temp=51),
                          row(temp=True), row(when="invalid")])
        self.assertEqual("MISSING_OR_INVALID_TEMPERATURE_AT_EXACT_TIME", missing["entries"][0]["reason"])
        self.assertIsNone(missing["entries"][0]["historyTemperatureC"])
        conflict = compare(7, START, END, [feedback()], [row(), row(temp=21)])
        self.assertEqual("CONFLICTING_TEMPERATURES_AT_EXACT_TIME", conflict["entries"][0]["reason"])

    def test_machine_timestamp_precision_and_kst_time_fallback(self):
        raw_time = {"sensor": 7, "time": "Fri Nov 20 09:00:00 2026", "temp": 21}
        result = compare(7, START, END, [feedback()], [raw_time])
        self.assertEqual(21, result["entries"][0]["historyTemperatureC"])
        target = feedback(collected="2026-11-20T00:00:00.000000001Z")
        seconds = int(START.timestamp())
        almost = row(when=f"{seconds}.000000002")
        exact = row(when=f"{seconds}.000000001", temp=22)
        result = compare(7, START, END, [target], [almost, exact])
        self.assertEqual(22, result["entries"][0]["historyTemperatureC"])

    def test_field_time_is_reported_separately_and_history_uses_collected_time(self):
        collected = "2026-11-20T00:00:05Z"
        target = feedback(collected=collected)
        target["measurement"]["measuredAt"] = "2026-11-20T00:00:04Z"
        report = compare(7, START, END, [target], [
            row(when=collected, temp=23), row(when=target["measurement"]["measuredAt"], temp=30),
        ])
        entry = report["entries"][0]
        self.assertEqual("2026-11-20T00:00:04Z", entry["fieldMeasuredAt"])
        self.assertEqual(collected, entry["historyMeasuredAt"])
        self.assertEqual(1.5, entry["differenceC"])

    def test_history_alone_does_not_count_as_comparison(self):
        for patch, reason in [
            ({"measurement": None}, "NO_LINKED_FIELD_MEASUREMENT"),
            ({"measurement": dict(feedback()["measurement"], sensorNo=8)},
             "FIELD_MEASUREMENT_LINK_MISMATCH"),
            ({"measurement": dict(feedback()["measurement"], temperatureC=None)},
             "MISSING_OR_INVALID_FIELD_TEMPERATURE"),
            ({"measurement": dict(feedback()["measurement"], measuredAt="2026-11-19T23:59:59Z")},
             "FIELD_MEASUREMENT_OUTSIDE_PERIOD"),
        ]:
            with self.subTest(reason=reason):
                entry = compare(7, START, END, [dict(feedback(), **patch)], [row()])["entries"][0]
                self.assertEqual("UNAVAILABLE", entry["result"])
                self.assertEqual(reason, entry["reason"])
                self.assertEqual(20, entry["historyTemperatureC"])
                self.assertIsNone(entry["differenceC"])


if __name__ == "__main__":
    unittest.main()
