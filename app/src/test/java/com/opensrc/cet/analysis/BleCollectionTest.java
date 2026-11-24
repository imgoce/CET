package com.opensrc.cet.analysis;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BleCollectionTest {
    private static final Instant START = Instant.parse("2026-11-23T03:00:00Z");
    private static final String MAC = "88:A2:9E:9B:5F:09";
    private final BleMeasurementParser<Reading> parser = new BleMeasurementParser<>(Reading::new);
    private final CollectionWindowSelector<Reading> selector = new CollectionWindowSelector<>(
            reading -> reading.sensorNo, reading -> reading.measuredAt,
            reading -> reading.collectedAt, reading -> reading.rssi);

    @Test
    public void mapsAllRegisteredSensorsAndRejectsWrongBleAliases() {
        int[] course = {7, 15, 31, 32, 33};
        for (int i = 0; i < course.length; i++) {
            assertEquals(course[i], SensorRegistry.fromBleName("opensrc" + (i + 1)).getAsInt());
            assertFalse(SensorRegistry.fromBleName(SensorRegistry.toPublicName(course[i])).isPresent());
        }
        int labCount = 0;
        for (int number = 1; number <= 34; number++) {
            if (SensorRegistry.fromBleName(SensorRegistry.toPublicName(number)).isPresent()) {
                labCount++;
            }
        }
        assertEquals(29, labCount);
        assertEquals("sensor 07", SensorRegistry.toPublicName(7));
        for (String invalid : Arrays.asList(null, "sensor 00", "sensor 35", "sensor 1", "pws01",
                "vs01", "vs02", "opensrc6")) {
            assertFalse(SensorRegistry.fromBleName(invalid).isPresent());
        }
    }

    @Test
    public void decodesLabPacketAndPreservesRealZero() {
        Reading reading = parse("sensor 01", lab(700, -250, 0xffff_ffffL), -85).get();
        assertEquals(1, reading.sensorNo);
        assertEquals(MAC, reading.mac);
        assertEquals(START, reading.collectedAt);
        assertEquals(Instant.ofEpochSecond(0xffff_ffffL), reading.measuredAt);
        assertEquals(-85, reading.rssi);
        assertEquals(Double.valueOf(-2.5), reading.temp);
        assertEquals(Integer.valueOf(700), reading.co2);
        assertNull(reading.humidity);
        assertNull(reading.eco2);
        assertNull(reading.aqi);
        assertNull(reading.tvoc);
        assertEquals(Double.valueOf(0), parse("sensor 01", lab(400, 0, 1), -85).get().temp);
    }

    @Test
    public void decodesCoursePacketIgnoringTrailingByte() {
        Reading reading = parse("opensrc1", course(2950, 6550, 5000), -58).get();
        assertEquals(7, reading.sensorNo);
        assertEquals(Double.valueOf(29.5), reading.temp);
        assertEquals(Double.valueOf(65.5), reading.humidity);
        assertEquals(Integer.valueOf(5000), reading.eco2);
        assertEquals(Integer.valueOf(200), reading.aqi);
        assertEquals(Integer.valueOf(60000), reading.tvoc);
        assertEquals(START, reading.measuredAt);
        assertNull(reading.co2);
    }

    @Test
    public void acceptsExactlyThirteenByteCoursePacket() {
        byte[] packet = Arrays.copyOf(course(-250, 3000, 715), 13);
        Reading reading = parse("opensrc1", packet, -85).get();
        assertEquals(Double.valueOf(-2.5), reading.temp);
        assertEquals(Double.valueOf(30), reading.humidity);
        assertEquals(Integer.valueOf(715), reading.eco2);
        assertEquals(START, reading.measuredAt);
    }

    @Test
    public void selectsNextSensorWhenStrongerAdvertisementIsInvalid() {
        Optional<Reading> invalid = parse("sensor 01", lab(399, 2900, START.getEpochSecond()), -40);
        assertFalse(invalid.isPresent());
        Reading valid = windowReading("sensor 02", 0, 1, -72);
        CollectionWindowSelector.Selection<Reading> selection = selector.select(START,
                START.plusSeconds(10), Collections.singletonList(valid),
                (number, timestamp) -> false).get();
        assertEquals(2, selection.candidateSensorNo().getAsInt());
        assertEquals(Collections.singletonList(valid), selection.measurements());
    }

    @Test
    public void rejectsMalformedAndOutOfRangeData() {
        byte[] valid = lab(700, 2900, 1);
        assertFalse(parse("sensor 01", valid, -86).isPresent());
        assertFalse(parse("sensor 01", Arrays.copyOf(valid, 7), -85).isPresent());
        assertFalse(parse("sensor 01", Arrays.copyOf(valid, 9), -85).isPresent());
        assertFalse(parse("opensrc1", new byte[12], -85).isPresent());
        assertFalse(parse("sensor 07", valid, -85).isPresent());
        assertFalse(parser.parse("sensor 01", valid, MAC, -85, null).isPresent());
        assertFalse(parser.parse("sensor 01", valid, "", -85, START).isPresent());
        assertFalse(parse("sensor 01", lab(399, 0, 1), -85).isPresent());
        assertFalse(parse("sensor 01", lab(5001, 0, 1), -85).isPresent());
        assertFalse(parse("sensor 01", lab(700, -3001, 1), -85).isPresent());
        assertFalse(parse("opensrc1", course(5001, 0, 700), -85).isPresent());
        assertFalse(parse("opensrc2", course(0, 10001, 700), -85).isPresent());
        assertTrue(parse("opensrc5", course(-3000, 0, 400), -85).isPresent());
        assertTrue(parse("opensrc5", course(5000, 10000, 5000), -85).isPresent());
    }

    @Test
    public void allowsFaultyTemperatureAndHumidityOnlyFor31And32() {
        for (String name : Arrays.asList("opensrc3", "opensrc4")) {
            Reading reading = parse(name, course(6000, 65535, 715), -85).get();
            assertNull(reading.temp);
            assertNull(reading.humidity);
            assertEquals(Integer.valueOf(715), reading.eco2);
            assertEquals(Integer.valueOf(200), reading.aqi);
            assertEquals(Integer.valueOf(60000), reading.tvoc);
            assertFalse(parse(name, course(0, 0, 399), -85).isPresent());
        }
    }

    @Test
    public void selectsLatestPerSensorThenStrongestDuplicateAndOneCandidate() {
        Reading old = windowReading("sensor 01", 0, 1, -40);
        Reading weakDuplicate = windowReading("sensor 01", 1, 2, -80);
        Reading strongDuplicate = windowReading("sensor 01", 1, 3, -55);
        Reading other = windowReading("sensor 02", 1, 4, -72);
        CollectionWindowSelector.Selection<Reading> selected = selector.select(START,
                START.plusSeconds(10), Arrays.asList(other, strongDuplicate, old, weakDuplicate),
                (number, timestamp) -> false).get();
        assertEquals(Arrays.asList(strongDuplicate, other), selected.measurements());
        assertEquals(1, selected.candidateSensorNo().getAsInt());
        assertEquals(2, selector.select(START, START.plusSeconds(10), Arrays.asList(old,
                weakDuplicate, other), (number, timestamp) -> false).get().candidateSensorNo().getAsInt());
    }

    @Test
    public void breaksTiesBySensorNumberAndSkipsAlreadyHandledMeasurements() {
        Reading lower = windowReading("sensor 01", 1, 2, -55);
        Reading higher = windowReading("sensor 02", 1, 3, -55);
        assertEquals(1, selector.select(START, START.plusSeconds(10), Arrays.asList(higher, lower),
                (number, timestamp) -> false).get().candidateSensorNo().getAsInt());
        CollectionWindowSelector.Selection<Reading> selected = selector.select(START,
                START.plusSeconds(10), Arrays.asList(higher, lower),
                (number, timestamp) -> number == 1 && timestamp.equals(lower.measuredAt)).get();
        assertEquals(2, selected.candidateSensorNo().getAsInt());
        assertSame(higher, selected.measurements().get(0));
        assertEquals(1, selected.measurements().size());
    }

    @Test
    public void requiresCompletedWindowAndUsesHalfOpenReceiveTimes() {
        Reading before = windowReading("sensor 01", 0, -1, -40);
        Reading atStart = windowReading("sensor 02", 0, 0, -80);
        Reading atEnd = windowReading("sensor 03", 0, 10, -40);
        assertFalse(selector.select(START, START.plusSeconds(10).minusNanos(1),
                Collections.singletonList(atStart), (number, timestamp) -> false).isPresent());
        assertEquals(Collections.singletonList(atStart), selector.select(START, START.plusSeconds(10),
                Arrays.asList(before, atStart, atEnd), (number, timestamp) -> false).get().measurements());
        CollectionWindowSelector.Selection<Reading> empty = selector.select(START,
                START.plusSeconds(10), Arrays.asList(before, atEnd), (number, timestamp) -> false).get();
        assertTrue(empty.measurements().isEmpty());
        assertFalse(empty.candidateSensorNo().isPresent());
    }

    private Optional<Reading> parse(String name, byte[] data, int rssi) {
        return parser.parse(name, data, MAC, rssi, START);
    }

    private Reading windowReading(String name, long measured, long received, int rssi) {
        return parser.parse(name, lab(700, 2900, START.plusSeconds(measured).getEpochSecond()),
                MAC, rssi, START.plusSeconds(received)).get();
    }

    private static byte[] lab(int co2, int temp, long epochSeconds) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort((short) co2)
                .putShort((short) temp).putInt((int) epochSeconds).array();
    }

    private static byte[] course(int temp, int humidity, int eco2) {
        return ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).putShort((short) temp)
                .putShort((short) humidity).put((byte) 200).putShort((short) 60000)
                .putShort((short) eco2).putInt((int) START.getEpochSecond()).put((byte) 42).array();
    }

    private static final class Reading {
        private final int sensorNo;
        private final String mac;
        private final Instant measuredAt;
        private final Instant collectedAt;
        private final int rssi;
        private final Double temp;
        private final Double humidity;
        private final Integer co2;
        private final Integer eco2;
        private final Integer aqi;
        private final Integer tvoc;

        private Reading(int sensorNo, String mac, Instant measuredAt, Instant collectedAt, int rssi,
                Double temp, Double humidity, Integer co2, Integer eco2, Integer aqi, Integer tvoc) {
            this.sensorNo = sensorNo;
            this.mac = mac;
            this.measuredAt = measuredAt;
            this.collectedAt = collectedAt;
            this.rssi = rssi;
            this.temp = temp;
            this.humidity = humidity;
            this.co2 = co2;
            this.eco2 = eco2;
            this.aqi = aqi;
            this.tvoc = tvoc;
        }
    }
}
