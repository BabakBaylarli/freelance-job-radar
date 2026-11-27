package com.babakbaylarli.jobradar.source;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * FL.ru official project RSS ({@code /rss/all.xml?category=5} = programming). The feed list is configurable, e.g. add
 * {@code /rss/all.xml?category=5&subcategory=...}. The budget is part of the title ("... (Бюджет: 10 000 ₽)") and is
 * parsed out of it.
 */
@Component
@ConditionalOnProperty(name = "jobradar.sources.fl-ru.enabled", havingValue = "true", matchIfMissing = true)
public class FlRuSource implements JobSource {

    public static final String ID = "flru";
    private static final Pattern BUDGET = Pattern.compile("^(.*?)\\s*\\(Бюджет:\\s*([\\d\\s\\u00a0]+?)\\s*([^\\d\\s)]*)\\s*\\)\\s*$", Pattern.DOTALL);
    private static final Pattern PROJECT_ID = Pattern.compile("/projects/(\\d+)");
    private static final Logger log = LoggerFactory.getLogger(FlRuSource.class);

    private final JobRadarProperties.FlRu config;
    private final HttpFetcher fetcher;

    public FlRuSource(JobRadarProperties properties, HttpFetcher fetcher) {
        this.config = properties.sources().flRu();
        this.fetcher = fetcher;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "FL.ru";
    }

    @Override
    public Duration pollInterval() {
        return config.pollInterval();
    }

    @Override
    public List<Job> fetch() {
        Map<String, Job> unique = new LinkedHashMap<>();
        SourceException lastFailure = null;
        int failed = 0;
        for (String feed : config.feeds()) {
            try {
                for (Job job : parse(fetcher.get(config.baseUrl() + feed, "application/rss+xml, application/xml"))) {
                    unique.putIfAbsent(job.externalId(), job);
                }
            } catch (SourceException e) {
                failed++;
                lastFailure = e;
                log.warn("FL.ru feed {} failed: {}", feed, e.getMessage());
            }
        }
        if (failed > 0 && failed == config.feeds().size()) {
            throw lastFailure;
        }
        return new ArrayList<>(unique.values());
    }

    List<Job> parse(String xml) {
        List<Job> result = new ArrayList<>();
        for (Map<String, String> item : RssFeed.items(xml, "FL.ru")) {
            String link = item.getOrDefault("link", "");
            String rawTitle = item.getOrDefault("title", "");
            if (link.isBlank() || rawTitle.isBlank()) {
                continue;
            }
            String title = Jsoup.parse(rawTitle).text().strip(); // titles contain entities such as &#8381; inside CDATA
            Budget budget = null;
            Matcher m = BUDGET.matcher(title);
            if (m.matches()) {
                title = m.group(1).strip();
                budget = Budget.of(new BigDecimal(m.group(2).replaceAll("[\\s\\u00a0]", "")), null, currency(m.group(3)), false);
            }
            Matcher id = PROJECT_ID.matcher(link);
            List<String> tags = new ArrayList<>();
            Arrays.stream(item.getOrDefault("category", "").split("/")).map(String::strip).filter(t -> !t.isEmpty()).forEach(tags::add);
            result.add(new Job(ID, id.find() ? id.group(1) : link, title, "", link, tags,
                    HtmlText.toPlain(item.getOrDefault("description", "")), null, parseDate(item.get("pubDate")),
                    Market.RU, JobType.FREELANCE_PROJECT, budget));
        }
        return result;
    }

    private static String currency(String symbol) {
        return switch (symbol) {
            case "$", "usd", "USD" -> "USD";
            case "€", "eur", "EUR" -> "EUR";
            case "грн", "₴" -> "UAH";
            default -> "RUB"; // ₽, "руб", or nothing: FL.ru budgets are in roubles unless stated otherwise
        };
    }

    private static Instant parseDate(String value) {
        try {
            return value == null || value.isBlank() ? null : ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
