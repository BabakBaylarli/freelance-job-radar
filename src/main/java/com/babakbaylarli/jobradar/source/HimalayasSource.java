package com.babakbaylarli.jobradar.source;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Himalayas public jobs API (search endpoint). Anonymous use is rate limited, so there is one request per configured
 * query and poll; a free API key ({@code HIMALAYAS_API_KEY}) is sent as a Bearer token when present. The link back is
 * the canonical Himalayas job page ({@code guid}) and every message says "via Himalayas".
 */
@Component
@ConditionalOnProperty(name = "jobradar.sources.himalayas.enabled", havingValue = "true", matchIfMissing = true)
public class HimalayasSource implements JobSource {

    public static final String ID = "himalayas";
    private static final int MAX_LISTED_LOCATIONS = 8;
    private static final Logger log = LoggerFactory.getLogger(HimalayasSource.class);

    private final JobRadarProperties.Himalayas config;
    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;

    public HimalayasSource(JobRadarProperties properties, HttpFetcher fetcher, ObjectMapper mapper) {
        this.config = properties.sources().himalayas();
        this.fetcher = fetcher;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Himalayas";
    }

    @Override
    public Duration pollInterval() {
        return config.pollInterval();
    }

    @Override
    public List<Job> fetch() {
        Map<String, String> headers = config.token() == null || config.token().isBlank()
                ? Map.of() : Map.of("Authorization", "Bearer " + config.token());
        Map<String, Job> unique = new LinkedHashMap<>();
        SourceException lastFailure = null;
        int failed = 0;
        for (String query : config.queries()) {
            try {
                String url = config.baseUrl() + "/jobs/api/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
                for (Job job : parse(fetcher.get(url, "application/json", headers))) {
                    unique.putIfAbsent(job.externalId(), job);
                }
            } catch (SourceException e) {
                failed++;
                lastFailure = e;
                log.warn("Himalayas query '{}' failed: {}", query, e.getMessage());
            }
        }
        if (failed > 0 && failed == config.queries().size()) {
            throw lastFailure;
        }
        return new ArrayList<>(unique.values());
    }

    List<Job> parse(String json) {
        JsonNode jobs;
        try {
            JsonNode root = mapper.readTree(json);
            if (root.has("ok") && !root.path("ok").asBoolean(true)) {
                throw new SourceException("Himalayas: " + root.path("errors").asText("request rejected"));
            }
            jobs = root.path("jobs");
        } catch (SourceException e) {
            throw e;
        } catch (Exception e) {
            throw new SourceException("Himalayas returned invalid JSON", e);
        }
        List<Job> result = new ArrayList<>();
        for (JsonNode j : jobs) {
            String guid = j.path("guid").asText(j.path("applicationLink").asText(""));
            String title = j.path("title").asText("");
            if (guid.isBlank() || title.isBlank()) {
                continue;
            }
            List<String> tags = new ArrayList<>();
            for (String field : List.of("categories", "seniority", "employmentType")) {
                JsonNode node = j.path(field);
                if (node.isArray()) {
                    node.forEach(v -> tags.add(v.asText()));
                } else if (node.isTextual()) {
                    tags.add(node.asText());
                }
            }
            // Worldwide jobs list every single country; collapse long lists so tags stay readable.
            JsonNode locations = j.path("locationRestrictions");
            if (locations.size() > MAX_LISTED_LOCATIONS) {
                tags.add("Worldwide");
            } else {
                locations.forEach(v -> tags.add(v.asText()));
            }
            long pub = j.path("pubDate").asLong(0);
            Budget budget = Budget.of(numeric(j.path("minSalary")), numeric(j.path("maxSalary")), j.path("currency").asText(null), false);
            String salary = budget == null ? null : budget.display() + " / " + j.path("salaryPeriod").asText("year");
            result.add(new Job(ID, guid, title.strip(), j.path("companyName").asText("").strip(), guid, tags,
                    HtmlText.toPlain(j.path("description").asText(j.path("excerpt").asText(""))), salary,
                    pub > 0 ? Instant.ofEpochSecond(pub) : null, Market.INTERNATIONAL, JobType.REMOTE_JOB, null));
        }
        return result;
    }

    private static Number numeric(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }
}
