package com.babakbaylarli.jobradar.source;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DOU.ua official vacancy RSS ({@code /vacancies/feeds/?category=Java&remote}). One request per configured category.
 * Item titles look like "Senior Java Developer в Company, Київ, віддалено".
 */
@Component
@ConditionalOnProperty(name = "jobradar.sources.dou.enabled", havingValue = "true", matchIfMissing = true)
public class DouSource implements JobSource {

    public static final String ID = "dou";
    private static final Pattern VACANCY_ID = Pattern.compile("/vacancies/(\\d+)");
    private static final Logger log = LoggerFactory.getLogger(DouSource.class);

    private final JobRadarProperties.Dou config;
    private final HttpFetcher fetcher;

    public DouSource(JobRadarProperties properties, HttpFetcher fetcher) {
        this.config = properties.sources().dou();
        this.fetcher = fetcher;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "DOU";
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
        for (String category : config.categories()) {
            String url = config.baseUrl() + "/vacancies/feeds/?category=" + URLEncoder.encode(category, StandardCharsets.UTF_8)
                    + (config.remote() ? "&remote" : "");
            try {
                for (Job job : parse(fetcher.get(url, "application/rss+xml, application/xml"))) {
                    unique.putIfAbsent(job.externalId(), job);
                }
            } catch (SourceException e) {
                failed++;
                lastFailure = e;
                log.warn("DOU category '{}' failed: {}", category, e.getMessage());
            }
        }
        if (failed > 0 && failed == config.categories().size()) {
            throw lastFailure;
        }
        return new ArrayList<>(unique.values());
    }

    List<Job> parse(String xml) {
        List<Job> result = new ArrayList<>();
        for (Map<String, String> item : RssFeed.items(xml, "DOU")) {
            String link = item.getOrDefault("link", "");
            String rawTitle = item.getOrDefault("title", "");
            if (link.isBlank() || rawTitle.isBlank()) {
                continue;
            }
            String title = rawTitle;
            String company = "";
            List<String> tags = new ArrayList<>();
            int at = rawTitle.lastIndexOf(" в ");
            if (at > 0) {
                title = rawTitle.substring(0, at).strip();
                String[] rest = rawTitle.substring(at + 3).split(",");
                company = rest[0].strip();
                for (int i = 1; i < rest.length; i++) {
                    tags.add(rest[i].strip());
                }
            }
            Matcher id = VACANCY_ID.matcher(link);
            result.add(new Job(ID, id.find() ? id.group(1) : link, title, company, link, tags,
                    HtmlText.toPlain(item.getOrDefault("description", "")), null, parseDate(item.get("pubDate")),
                    Market.UA, JobType.VACANCY, null));
        }
        return result;
    }

    private static Instant parseDate(String value) {
        try {
            return value == null || value.isBlank() ? null : ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
