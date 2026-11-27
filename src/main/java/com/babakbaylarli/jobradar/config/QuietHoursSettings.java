package com.babakbaylarli.jobradar.config;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * A daily window (in a given time zone) during which no live notifications are sent.
 * The window may wrap around midnight (e.g. 22:00 - 07:00). {@code start == end} means "no window".
 */
public record QuietHoursSettings(boolean enabled, LocalTime start, LocalTime end, ZoneId zone) {

    public static QuietHoursSettings disabled() {
        return new QuietHoursSettings(false, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ZoneId.of("UTC"));
    }

    public boolean isQuiet(Instant instant) {
        if (!enabled || start.equals(end)) {
            return false;
        }
        LocalTime local = instant.atZone(zone).toLocalTime();
        if (start.isBefore(end)) {
            return !local.isBefore(start) && local.isBefore(end);
        }
        return !local.isBefore(start) || local.isBefore(end);
    }

    public String describe() {
        return enabled ? "%s-%s %s".formatted(start, end, zone) : "off";
    }
}
