package com.babakbaylarli.jobradar.config;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** All application settings, bound from {@code application.yml} / {@code config/keywords.yml} / env vars. */
@ConfigurationProperties(prefix = "jobradar")
public record JobRadarProperties(
        @DefaultValue Telegram telegram,
        @DefaultValue Ai ai,
        @DefaultValue Matching matching,
        @DefaultValue Notifications notifications,
        @DefaultValue Http http,
        @DefaultValue Sources sources,
        @DefaultValue("config/profile.md") String profilePath) {

    public record Telegram(String botToken, Long ownerChatId) {
        public boolean botConfigured() {
            return botToken != null && !botToken.isBlank();
        }
    }

    public record Ai(
            String apiKey,
            @DefaultValue("claude-sonnet-5-5") String model,
            String baseUrl,
            @DefaultValue("2000") int maxTokens,
            @DefaultValue("medium") String effort,
            @DefaultValue("60s") Duration timeout) {
        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Keyword(String term, int weight) {}

    public record Matching(
            @DefaultValue List<Keyword> include,
            @DefaultValue List<String> exclude,
            @DefaultValue("4") int minScore,
            @DefaultValue("7d") Duration notifyMaxAge) {}

    public record Notifications(
            @DefaultValue("6") int maxPerHour,
            @DefaultValue("10") int digestSize,
            @DefaultValue("6h") Duration digestMinInterval,
            QuietHoursSettings quietHours) {
        public Notifications {
            if (quietHours == null) {
                quietHours = new QuietHoursSettings(true, LocalTime.MIDNIGHT, LocalTime.of(8, 0), ZoneId.of("Asia/Baku"));
            }
        }
    }

    public record Http(
            @DefaultValue("freelance-job-radar/0.1 (+https://github.com/BabakBaylarli/freelance-job-radar)") String userAgent,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("20s") Duration readTimeout,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("1s") Duration initialBackoff) {}

    public record Sources(
            @DefaultValue WeWorkRemotely weWorkRemotely,
            @DefaultValue Remotive remotive,
            @DefaultValue RemoteOk remoteOk,
            @DefaultValue HackerNews hackerNews) {}

    public record WeWorkRemotely(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("3h") Duration pollInterval,
            @DefaultValue("https://weworkremotely.com") String baseUrl,
            @DefaultValue({"/categories/remote-back-end-programming-jobs.rss", "/categories/remote-programming-jobs.rss"})
                    List<String> feeds) {}

    public record Remotive(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("6h") Duration pollInterval,
            @DefaultValue("https://remotive.com") String baseUrl,
            @DefaultValue("software-dev") String category) {}

    public record RemoteOk(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("6h") Duration pollInterval,
            @DefaultValue("https://remoteok.com") String baseUrl) {}

    public record HackerNews(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("6h") Duration pollInterval,
            @DefaultValue("https://hn.algolia.com") String baseUrl,
            @DefaultValue("true") boolean remoteOnly) {}
}
