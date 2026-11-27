package com.opensrc.cet.analysis;

import org.junit.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MapEnvironmentTest {
    private static final Instant OLD = Instant.parse("2026-11-20T03:00:00Z");
    private static final Instant NEW = OLD.plusSeconds(60);

    @Test
    public void selectsLatestTimeAndPrefersMobileForTiesRegardlessOfInputOrder() {
        Row direct = new Row(7, NEW, "direct");
        Row mobile = new Row(7, NEW, "mobile");
        for (List<Row> input : Arrays.asList(Arrays.asList(direct, mobile), Arrays.asList(mobile, direct))) {
            assertEquals(Collections.singletonList(mobile), select(input));
        }
        assertEquals(Collections.singletonList(direct), select(Arrays.asList(
                new Row(7, OLD, "mobile"), direct)));
        assertEquals(Collections.singletonList(mobile), select(Arrays.asList(
                mobile, new Row(7, OLD, "direct"))));
    }

    @Test
    public void excludesUnknownSensorsAndUnparseableTimesAndMasksBrokenTemperature() {
        Row valid = new Row(31, OLD, "direct");
        assertEquals(Collections.singletonList(valid), select(Arrays.asList(
                new Row(null, NEW, "mobile"), new Row(35, NEW, "mobile"),
                new Row(0, NEW, "mobile"), new Row(31, null, "mobile"), valid)));
        assertNull(MapReadingSelector.displayTemperature(31, 23.0));
        assertNull(MapReadingSelector.displayTemperature(32, 23.0));
        assertEquals(Double.valueOf(0), MapReadingSelector.displayTemperature(18, 0.0));
    }

    @Test
    public void classifiesThresholdsIncludingMissing() {
        assertStates(10.0, 30.0, "COLD", "DRY");
        assertStates(10.01, 30.01, "NORMAL", "NORMAL");
        assertStates(27.99, 64.99, "NORMAL", "NORMAL");
        assertStates(28.0, 65.0, "HOT", "HUMID");
        assertStates(null, null, "MISSING", "MISSING");
    }

    @Test
    public void keepsLatestValidValueAndTimestampIndependentlyWithoutExpiry() {
        SiteEnvironmentRules site = new SiteEnvironmentRules(7);
        site.include(7, 29.0, NEW, null, null, 700, NEW);
        site.include(7, 20.0, OLD, 70.0, OLD, 600, OLD);
        site.include(7, 51.0, NEW.plusSeconds(1), 101.0, NEW, 5001, NEW);
        site.include(7, 10.0, null, 20.0, null, 800, null);
        assertEquals(Arrays.asList(7, "sensor 07", 29.0, NEW, 70.0, OLD, 700, NEW),
                snapshot(site));
    }

    @Test
    public void switchingFromHumidSiteToLabSiteDoesNotCarryOverHumidity() {
        SiteEnvironmentRules first = new SiteEnvironmentRules(7);
        first.include(7, 23.0, OLD, 70.0, OLD, 715, OLD);
        assertEquals(SiteEnvironmentRules.HumidityState.HUMID, first.humidityState());

        SiteEnvironmentRules selected = new SiteEnvironmentRules(18);
        selected.include(18, 29.0, OLD, null, null, null, null);
        selected.include(7, 23.0, NEW, 70.0, NEW, 715, NEW);
        assertEquals(SiteEnvironmentRules.TemperatureState.HOT, selected.temperatureState());
        assertEquals(SiteEnvironmentRules.HumidityState.MISSING, selected.humidityState());
        assertEquals(Arrays.asList(18, "sensor 18", 29.0, OLD, null, null, null, null),
                snapshot(selected));
    }

    @Test
    public void ignoresHistoricalTemperatureAndHumidityOfBrokenSensors() {
        for (int sensorNo : Arrays.asList(31, 32)) {
            SiteEnvironmentRules site = new SiteEnvironmentRules(sensorNo);
            assertEquals(Arrays.asList(sensorNo, "sensor " + sensorNo,
                    null, null, null, null, null, null), snapshot(site));
            site.include(sensorNo, 29.0, OLD, 70.0, OLD, 715, OLD);
            assertEquals(SiteEnvironmentRules.TemperatureState.MISSING, site.temperatureState());
            assertEquals(SiteEnvironmentRules.HumidityState.MISSING, site.humidityState());
            assertEquals(Arrays.asList(sensorNo, "sensor " + sensorNo,
                    null, null, null, null, 715, OLD), snapshot(site));
        }
    }

    private void assertStates(Double temperature, Double humidity, String expectedTemp,
            String expectedHumidity) {
        SiteEnvironmentRules site = new SiteEnvironmentRules(7);
        site.include(7, temperature, OLD, humidity, OLD, null, null);
        assertEquals(expectedTemp, site.temperatureState().name());
        assertEquals(expectedHumidity, site.humidityState().name());
    }

    private List<Object> snapshot(SiteEnvironmentRules site) {
        return site.toEnvironment(null, (number, name, temp, tempTime, humidity, humidityTime,
                eco2, eco2Time) -> Arrays.asList(number, name, temp, tempTime, humidity,
                humidityTime, eco2, eco2Time));
    }

    private List<Row> select(List<Row> rows) {
        return MapReadingSelector.selectLatest(rows, row -> row.sensorNo,
                row -> row.measuredAt, row -> row.mode);
    }

    private static final class Row {
        private final Integer sensorNo;
        private final Instant measuredAt;
        private final String mode;

        private Row(Integer sensorNo, Instant measuredAt, String mode) {
            this.sensorNo = sensorNo;
            this.measuredAt = measuredAt;
            this.mode = mode;
        }
    }
}
