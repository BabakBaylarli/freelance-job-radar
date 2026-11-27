package com.babakbaylarli.jobradar.config;

import com.babakbaylarli.jobradar.source.Market;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** All application settings, bound from {@code application.yml} / {@code config/*.yml} / env vars. */
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

    /** A weighted keyword; {@code aliases} are other spellings/languages that count as the same keyword. */
    public record Keyword(String term, int weight, @DefaultValue List<String> aliases) {
        public static Keyword of(String term, int weight) {
            return new Keyword(term, weight, List.of());
        }

        public static Keyword of(String term, int weight, String... aliases) {
            return new Keyword(term, weight, List.of(aliases));
        }
    }

    public record Matching(
            @DefaultValue List<Keyword> include,
            @DefaultValue List<String> exclude,
            @DefaultValue("4") int minScore,
            @DefaultValue("7d") Duration notifyMaxAge,
            /** Minimum fixed budget per currency code; applies to freelance projects only. */
            @DefaultValue Map<String, BigDecimal> minBudget) {}

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
            @DefaultValue HackerNews hackerNews,
            @DefaultValue Freelancer freelancer,
            @DefaultValue Himalayas himalayas,
            @DefaultValue Jobicy jobicy,
            @DefaultValue WorkingNomads workingNomads,
            @DefaultValue FlRu flRu,
            @DefaultValue Hh hh,
            @DefaultValue Freelancehunt freelancehunt,
            @DefaultValue Dou dou,
            @DefaultValue EmailAlerts emailAlerts,
            @DefaultValue TelegramChannels telegramChannels) {}

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

    /** Freelancer.com Projects API. {@code token} is optional (sent as {@code freelancer-oauth-v1}). */
    public record Freelancer(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("6h") Duration pollInterval,
            @DefaultValue("https://www.freelancer.com") String baseUrl,
            String token,
            @DefaultValue({"java", "spring boot"}) List<String> queries,
            @DefaultValue("30") int limit) {}

    /** Himalayas search API. Rate limited for anonymous use: one request per query; {@code token} (optional) raises the limit. */
    public record Himalayas(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("6h") Duration pollInterval,
            @DefaultValue("https://himalayas.app") String baseUrl,
            String token,
            @DefaultValue("java") List<String> queries) {}

    public record Jobicy(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("6h") Duration pollInterval,
            @DefaultValue("https://jobicy.com") String baseUrl,
            @DefaultValue({"java", "backend"}) List<String> tags,
            @DefaultValue("50") int count) {}

    public record WorkingNomads(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("6h") Duration pollInterval,
            @DefaultValue("https://www.workingnomads.com") String baseUrl) {}

    /** FL.ru official RSS; every entry in {@code feeds} is a path+query, e.g. {@code /rss/all.xml?category=5}. */
    public record FlRu(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("3h") Duration pollInterval,
            @DefaultValue("https://www.fl.ru") String baseUrl,
            @DefaultValue("/rss/all.xml?category=5") List<String> feeds) {}

    /** hh.ru API. Needs {@code contactEmail} for the mandatory {@code HH-User-Agent} header. */
    public record Hh(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("6h") Duration pollInterval,
            @DefaultValue("https://api.hh.ru") String baseUrl,
            String contactEmail,
            @DefaultValue({"java", "spring"}) List<String> queries,
            @DefaultValue("REMOTE") String workFormat,
            @DefaultValue({"PROJECT", "PART"}) List<String> employmentForms,
            @DefaultValue("50") int perPage) {}

    /** Freelancehunt API v2. Requires {@code token}; {@code skillIds} filter by skill (empty = all programming). */
    public record Freelancehunt(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("3h") Duration pollInterval,
            @DefaultValue("https://api.freelancehunt.com") String baseUrl,
            String token,
            @DefaultValue List<Integer> skillIds) {}

    public record Dou(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("6h") Duration pollInterval,
            @DefaultValue("https://jobs.dou.ua") String baseUrl,
            @DefaultValue("Java") List<String> categories,
            @DefaultValue("true") boolean remote) {}

    /** Job alert emails read over IMAP from a dedicated label/folder. */
    public record EmailAlerts(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("15m") Duration pollInterval,
            @DefaultValue("imap.gmail.com") String host,
            @DefaultValue("993") int port,
            String username,
            String appPassword,
            @DefaultValue("JobAlerts") String folder,
            @DefaultValue("30") int maxMessagesPerPoll) {
        public boolean credentialsPresent() {
            return username != null && !username.isBlank() && appPassword != null && !appPassword.isBlank();
        }
    }

    /** One public Telegram channel to read, and the market its posts belong to. */
    public record Channel(String username, Market market) {
        public Channel {
            market = market == null ? Market.INTERNATIONAL : market;
        }
    }

    /** Public Telegram channels read through the official Telegram client API (TDLight/MTProto). */
    public record TelegramChannels(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("30m") Duration pollInterval,
            String apiId,
            String apiHash,
            String phone,
            @DefaultValue("/data/tdlight") String sessionDir,
            @DefaultValue List<Channel> channels,
            @DefaultValue("50") int maxPostsPerChannel) {
        public boolean credentialsPresent() {
            return notBlank(apiId) && notBlank(apiHash) && notBlank(phone);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }
}
