package com.babakbaylarli.jobradar;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.config.JobRadarProperties.Ai;
import com.babakbaylarli.jobradar.config.JobRadarProperties.HackerNews;
import com.babakbaylarli.jobradar.config.JobRadarProperties.Http;
import com.babakbaylarli.jobradar.config.JobRadarProperties.Keyword;
import com.babakbaylarli.jobradar.config.JobRadarProperties.Matching;
import com.babakbaylarli.jobradar.config.JobRadarProperties.Notifications;
import com.babakbaylarli.jobradar.config.JobRadarProperties.RemoteOk;
import com.babakbaylarli.jobradar.config.JobRadarProperties.Remotive;
import com.babakbaylarli.jobradar.config.JobRadarProperties.Sources;
import com.babakbaylarli.jobradar.config.JobRadarProperties.Telegram;
import com.babakbaylarli.jobradar.config.JobRadarProperties.WeWorkRemotely;
import com.babakbaylarli.jobradar.config.QuietHoursSettings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Shared helpers for tests: properties builders and sample payload loading. */
public final class TestSupport {

    private TestSupport() {}

    public static String sample(String name) {
        try (InputStream in = TestSupport.class.getResourceAsStream("/samples/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing sample " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Matching matching(int minScore, List<Keyword> include, List<String> exclude) {
        return new Matching(include, exclude, minScore, Duration.ofDays(7));
    }

    /** Properties pointing every source at {@code baseUrl}, with millisecond backoff so retry tests are fast. */
    public static JobRadarProperties props(String baseUrl) {
        return props(baseUrl, true);
    }

    public static JobRadarProperties props(String baseUrl, boolean hnRemoteOnly) {
        return new JobRadarProperties(
                new Telegram("", null),
                new Ai("", "claude-sonnet-5-5", null, 2000, "medium", Duration.ofSeconds(30)),
                matching(4, List.of(), List.of()),
                new Notifications(6, 10, Duration.ofHours(6), QuietHoursSettings.disabled()),
                new Http("test-agent/1.0 (+https://example.test)", Duration.ofSeconds(2), Duration.ofSeconds(5), 3, Duration.ofMillis(1)),
                new Sources(
                        new WeWorkRemotely(true, Duration.ofHours(3), baseUrl, List.of("/feed-a.rss", "/feed-b.rss")),
                        new Remotive(true, Duration.ofHours(6), baseUrl, "software-dev"),
                        new RemoteOk(true, Duration.ofHours(6), baseUrl),
                        new HackerNews(true, Duration.ofHours(6), baseUrl, hnRemoteOnly)),
                "config/profile.md");
    }
}
