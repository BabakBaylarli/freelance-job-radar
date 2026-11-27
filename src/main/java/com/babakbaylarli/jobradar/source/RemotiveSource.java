package com.babakbaylarli.jobradar.source;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Remotive public API ({@code /api/remote-jobs?category=software-dev}).
 * <p>
 * Usage rules: poll at most a few times a day (default every 6h = 4 requests/day) and always link
 * back to the original job on remotive.com - we only ever store and show Remotive's own {@code url}.
 */
@Component
@ConditionalOnProperty(name = "jobradar.sources.remotive.enabled", havingValue = "true", matchIfMissing = true)
public class RemotiveSource implements JobSource {

    public static final String ID = "remotive";
    private static final Duration RECOMMENDED_MIN_INTERVAL = Duration.ofHours(6);
    private static final Logger log = LoggerFactory.getLogger(RemotiveSource.class);

    private final JobRadarProperties.Remotive config;
    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;

    public RemotiveSource(JobRadarProperties properties, HttpFetcher fetcher, ObjectMapper mapper) {
        this.config = properties.sources().remotive();
        this.fetcher = fetcher;
        this.mapper = mapper;
        if (config.pollInterval().compareTo(RECOMMENDED_MIN_INTERVAL) < 0) {
            log.warn("Remotive asks API users to poll at most a few times a day; poll-interval {} is more aggressive "
                    + "than the recommended {}", config.pollInterval(), RECOMMENDED_MIN_INTERVAL);
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Remotive";
    }

    @Override
    public Duration pollInterval() {
        return config.pollInterval();
    }

    @Override
    public List<Job> fetch() {
        String url = config.baseUrl() + "/api/remote-jobs?category="
                + URLEncoder.encode(config.category(), StandardCharsets.UTF_8);
        return parse(fetcher.get(url, "application/json"));
    }

    List<Job> parse(String json) {
        JsonNode jobs;
        try {
            jobs = mapper.readTree(json).path("jobs");
        } catch (Exception e) {
            throw new SourceException("Remotive returned invalid JSON", e);
        }
        List<Job> result = new ArrayList<>();
        for (JsonNode node : jobs) {
            String id = node.path("id").asText("");
            String url = node.path("url").asText("");
            String title = node.path("title").asText("");
            if (id.isBlank() || url.isBlank() || title.isBlank()) {
                log.debug("Skipping malformed Remotive entry: {}", node);
                continue;
            }
            List<String> tags = new ArrayList<>();
            node.path("tags").forEach(t -> tags.add(t.asText()));
            // Location goes into the tags so that country exclusions in the config can match it.
            String location = node.path("candidate_required_location").asText("");
            if (!location.isBlank()) {
                tags.add(location);
            }
            String salary = node.path("salary").asText("");
            result.add(new Job(
                    ID,
                    id,
                    title.strip(),
                    node.path("company_name").asText("").strip(),
                    url,
                    tags,
                    HtmlText.toPlain(node.path("description").asText("")),
                    salary.isBlank() ? null : salary,
                    parseDate(node.path("publication_date").asText(""))));
        }
        return result;
    }

    private static Instant parseDate(String value) {
        try {
            return value.isBlank() ? null : LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }
}
