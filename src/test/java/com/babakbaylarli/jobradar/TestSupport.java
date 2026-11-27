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
        return new Matching(include, exclude, minScore, Duration.ofDays(7), java.util.Map.of());
    }

    /** Properties pointing every source at {@code baseUrl}, with millisecond backoff so retry tests are fast. */
    public static JobRadarProperties props(String baseUrl) {
        return props(baseUrl, true);
    }

    public static JobRadarProperties props(String baseUrl, boolean hnRemoteOnly) {
        return props(baseUrl, hnRemoteOnly, true);
    }

    /** Same, but without any token / contact e-mail configured. */
    public static JobRadarProperties propsWithoutSecrets(String baseUrl) {
        return props(baseUrl, true, false);
    }

    private static JobRadarProperties props(String baseUrl, boolean hnRemoteOnly, boolean secrets) {
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
                        new HackerNews(true, Duration.ofHours(6), baseUrl, hnRemoteOnly),
                        new JobRadarProperties.Freelancer(true, Duration.ofHours(6), baseUrl, secrets ? "fl-token" : null, List.of("java"), 30),
                        new JobRadarProperties.Himalayas(true, Duration.ofHours(6), baseUrl, secrets ? "him-key" : null, List.of("java")),
                        new JobRadarProperties.Jobicy(true, Duration.ofHours(6), baseUrl, List.of("java"), 50),
                        new JobRadarProperties.WorkingNomads(true, Duration.ofHours(6), baseUrl),
                        new JobRadarProperties.FlRu(true, Duration.ofHours(3), baseUrl, List.of("/rss/all.xml?category=5")),
                        new JobRadarProperties.Hh(true, Duration.ofHours(6), baseUrl, secrets ? "me@example.org" : null, List.of("java"), "REMOTE", List.of("PROJECT", "PART"), 50),
                        new JobRadarProperties.Freelancehunt(true, Duration.ofHours(3), baseUrl, secrets ? "fh-token" : null, List.of()),
                        new JobRadarProperties.Dou(true, Duration.ofHours(6), baseUrl, List.of("Java"), true),
                        new JobRadarProperties.EmailAlerts(false, Duration.ofMinutes(15), "localhost", 993, null, null, "JobAlerts", 30),
                        new JobRadarProperties.TelegramChannels(false, Duration.ofMinutes(30), null, null, null, "/tmp/tdlight", List.of(), 50)),
                "config/profile.md");
    }
}
