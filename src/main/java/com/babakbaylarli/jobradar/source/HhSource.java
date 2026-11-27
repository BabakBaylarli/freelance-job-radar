package com.babakbaylarli.jobradar.source;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * hh.ru official API ({@code GET /vacancies}). Uses the current {@code work_format} and {@code employment_form}
 * parameters (the older {@code schedule}/{@code employment} are deprecated by hh.ru). hh.ru requires an identifying
 * {@code HH-User-Agent}; it is built as {@code FreelanceJobRadar/1.0 (<HH_CONTACT_EMAIL>)} and the source is skipped
 * when the email is not configured. Only the vacancy snippet is read - no extra request per vacancy.
 */
@Component
@ConditionalOnProperty(name = "jobradar.sources.hh.enabled", havingValue = "true", matchIfMissing = true)
public class HhSource implements JobSource {

    public static final String ID = "hh";
    private static final DateTimeFormatter HH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    private static final Logger log = LoggerFactory.getLogger(HhSource.class);

    private final JobRadarProperties.Hh config;
    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;

    public HhSource(JobRadarProperties properties, HttpFetcher fetcher, ObjectMapper mapper) {
        this.config = properties.sources().hh();
        this.fetcher = fetcher;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "hh.ru";
    }

    @Override
    public Duration pollInterval() {
        return config.pollInterval();
    }

    @Override
    public Optional<String> unavailableReason() {
        return config.contactEmail() == null || config.contactEmail().isBlank()
                ? Optional.of("HH_CONTACT_EMAIL is not set (hh.ru requires an identifying HH-User-Agent)")
                : Optional.empty();
    }

    @Override
    public List<Job> fetch() {
        String agent = "FreelanceJobRadar/1.0 (" + config.contactEmail().strip() + ")";
        Map<String, String> headers = Map.of("HH-User-Agent", agent, "User-Agent", agent);
        Map<String, Job> unique = new LinkedHashMap<>();
        SourceException lastFailure = null;
        int failed = 0;
        for (String query : config.queries()) {
            StringBuilder url = new StringBuilder(config.baseUrl()).append("/vacancies?text=")
                    .append(URLEncoder.encode(query, StandardCharsets.UTF_8))
                    .append("&work_format=").append(URLEncoder.encode(config.workFormat(), StandardCharsets.UTF_8))
                    .append("&per_page=").append(config.perPage()).append("&order_by=publication_time");
            config.employmentForms().forEach(form -> url.append("&employment_form=").append(URLEncoder.encode(form, StandardCharsets.UTF_8)));
            try {
                for (Job job : parse(fetcher.get(url.toString(), "application/json", headers))) {
                    unique.putIfAbsent(job.externalId(), job);
                }
            } catch (SourceException e) {
                failed++;
                lastFailure = e;
                log.warn("hh.ru query '{}' failed: {}", query, e.getMessage());
            }
        }
        if (failed > 0 && failed == config.queries().size()) {
            throw lastFailure;
        }
        return new ArrayList<>(unique.values());
    }

    List<Job> parse(String json) {
        JsonNode items;
        try {
            items = mapper.readTree(json).path("items");
        } catch (Exception e) {
            throw new SourceException("hh.ru returned invalid JSON", e);
        }
        List<Job> result = new ArrayList<>();
        for (JsonNode v : items) {
            String id = v.path("id").asText("");
            String url = v.path("alternate_url").asText("");
            String name = v.path("name").asText("");
            if (id.isBlank() || url.isBlank() || name.isBlank()) {
                continue;
            }
            List<String> tags = new ArrayList<>();
            for (String field : List.of("work_format", "employment_form")) {
                JsonNode node = v.path(field);
                if (node.isArray()) {
                    node.forEach(x -> tags.add(x.path("name").asText()));
                } else if (node.has("name")) {
                    tags.add(node.path("name").asText());
                }
            }
            for (String field : List.of("area", "schedule", "employment")) {
                if (v.path(field).has("name")) {
                    tags.add(v.path(field).path("name").asText());
                }
            }
            JsonNode snippet = v.path("snippet");
            String description = HtmlText.toPlain(snippet.path("requirement").asText("") + "<br>" + snippet.path("responsibility").asText(""));
            result.add(new Job(ID, id, name.strip(), v.path("employer").path("name").asText("").strip(), url, tags, description,
                    null, postedAt(v.path("published_at").asText("")), Market.RU, JobType.VACANCY, salary(v)));
        }
        return result;
    }

    private static Budget salary(JsonNode v) {
        JsonNode s = v.path("salary_range").isObject() ? v.path("salary_range") : v.path("salary");
        if (!s.isObject()) {
            return null;
        }
        String currency = s.path("currency").asText("");
        return Budget.of(number(s.path("from")), number(s.path("to")), "RUR".equals(currency) ? "RUB" : currency, false);
    }

    private static Number number(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }

    private static Instant postedAt(String value) {
        try {
            return value.isBlank() ? null : OffsetDateTime.parse(value, HH_TIME).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
