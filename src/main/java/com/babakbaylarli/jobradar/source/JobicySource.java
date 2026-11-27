package com.babakbaylarli.jobradar.source;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Jobicy remote jobs API v2. Their feed notice asks API users to credit Jobicy with a direct link and to send people to
 * the original job URL: we only ever show the {@code url} from the feed and label every message "via Jobicy".
 */
@Component
@ConditionalOnProperty(name = "jobradar.sources.jobicy.enabled", havingValue = "true", matchIfMissing = true)
public class JobicySource implements JobSource {

    public static final String ID = "jobicy";
    private static final Logger log = LoggerFactory.getLogger(JobicySource.class);

    private final JobRadarProperties.Jobicy config;
    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;

    public JobicySource(JobRadarProperties properties, HttpFetcher fetcher, ObjectMapper mapper) {
        this.config = properties.sources().jobicy();
        this.fetcher = fetcher;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Jobicy";
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
        for (String tag : config.tags()) {
            try {
                String url = config.baseUrl() + "/api/v2/remote-jobs?tag=" + URLEncoder.encode(tag, StandardCharsets.UTF_8) + "&count=" + config.count();
                for (Job job : parse(fetcher.get(url, "application/json"))) {
                    unique.putIfAbsent(job.externalId(), job);
                }
            } catch (SourceException e) {
                failed++;
                lastFailure = e;
                log.warn("Jobicy tag '{}' failed: {}", tag, e.getMessage());
            }
        }
        if (failed > 0 && failed == config.tags().size()) {
            throw lastFailure;
        }
        return new ArrayList<>(unique.values());
    }

    List<Job> parse(String json) {
        JsonNode jobs;
        try {
            jobs = mapper.readTree(json).path("jobs");
        } catch (Exception e) {
            throw new SourceException("Jobicy returned invalid JSON", e);
        }
        List<Job> result = new ArrayList<>();
        for (JsonNode j : jobs) {
            String id = j.path("id").asText("");
            String url = j.path("url").asText("");
            String title = j.path("jobTitle").asText("");
            if (id.isBlank() || url.isBlank() || title.isBlank()) {
                continue;
            }
            List<String> tags = new ArrayList<>();
            for (String field : List.of("jobIndustry", "jobType")) {
                j.path(field).forEach(v -> tags.add(v.asText()));
            }
            for (String field : List.of("jobGeo", "jobLevel")) {
                String value = j.path(field).asText("").replaceAll("\\s+", " ").strip();
                if (!value.isEmpty()) {
                    tags.add(value);
                }
            }
            result.add(new Job(ID, id, title.strip(), j.path("companyName").asText("").strip(), url, tags,
                    HtmlText.toPlain(j.path("jobDescription").asText(j.path("jobExcerpt").asText(""))),
                    salary(j), postedAt(j.path("pubDate").asText("")), Market.INTERNATIONAL, JobType.REMOTE_JOB, null));
        }
        return result;
    }

    private static String salary(JsonNode j) {
        long min = j.path("salaryMin").asLong(0);
        long max = j.path("salaryMax").asLong(0);
        if (min <= 0 && max <= 0) {
            return null;
        }
        NumberFormat fmt = NumberFormat.getIntegerInstance(Locale.US);
        String range = min > 0 && max > 0 ? fmt.format(min) + " - " + fmt.format(max) : fmt.format(Math.max(min, max));
        String currency = j.path("salaryCurrency").asText("");
        String period = j.path("salaryPeriod").asText("");
        return range + (currency.isBlank() ? "" : " " + currency) + (period.isBlank() ? "" : " / " + period);
    }

    private static Instant postedAt(String value) {
        try {
            return value.isBlank() ? null : OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
