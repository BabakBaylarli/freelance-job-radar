package com.babakbaylarli.jobradar.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class QuietHoursSettingsTest {

    private static final ZoneId BAKU = ZoneId.of("Asia/Baku"); // UTC+4, no DST

    @Test
    void morningWindowInTheConfiguredZone() {
        QuietHoursSettings q = new QuietHoursSettings(true, LocalTime.MIDNIGHT, LocalTime.of(8, 0), BAKU);

        assertThat(q.isQuiet(Instant.parse("2026-11-27T20:00:00Z"))).isTrue();  // 00:00 Baku
        assertThat(q.isQuiet(Instant.parse("2026-11-27T22:30:00Z"))).isTrue();  // 02:30 Baku
        assertThat(q.isQuiet(Instant.parse("2026-11-28T03:59:59Z"))).isTrue();  // 07:59:59 Baku
        assertThat(q.isQuiet(Instant.parse("2026-11-28T04:00:00Z"))).isFalse(); // 08:00 Baku - ends
        assertThat(q.isQuiet(Instant.parse("2026-11-27T12:00:00Z"))).isFalse(); // 16:00 Baku
        assertThat(q.isQuiet(Instant.parse("2026-11-27T19:59:59Z"))).isFalse(); // 23:59:59 Baku
    }

    @Test
    void windowCanWrapAroundMidnight() {
        QuietHoursSettings q = new QuietHoursSettings(true, LocalTime.of(22, 0), LocalTime.of(7, 0), ZoneId.of("UTC"));

        assertThat(q.isQuiet(Instant.parse("2026-11-27T23:00:00Z"))).isTrue();
        assertThat(q.isQuiet(Instant.parse("2026-11-27T06:59:00Z"))).isTrue();
        assertThat(q.isQuiet(Instant.parse("2026-11-27T07:00:00Z"))).isFalse();
        assertThat(q.isQuiet(Instant.parse("2026-11-27T21:59:00Z"))).isFalse();
    }

    @Test
    void disabledOrEmptyWindowIsNeverQuiet() {
        assertThat(QuietHoursSettings.disabled().isQuiet(Instant.parse("2026-11-27T01:00:00Z"))).isFalse();
        assertThat(new QuietHoursSettings(true, LocalTime.NOON, LocalTime.NOON, BAKU).isQuiet(Instant.now())).isFalse();
        assertThat(QuietHoursSettings.disabled().describe()).isEqualTo("off");
    }
}
