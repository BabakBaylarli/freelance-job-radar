package com.babakbaylarli.jobradar.source;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Freelancehunt API v2 ({@code GET /v2/projects}) with {@code Authorization: Bearer <FREELANCEHUNT_TOKEN>}. Optional
 * {@code skill-ids} narrow the list. Skipped (with a startup warning) when the token is not configured. The employer is
 * a private person in most cases, so only a company name is stored, never a personal name.
 */
@Component
@ConditionalOnProperty(name = "jobradar.sources.freelancehunt.enabled", havingValue = "true", matchIfMissing = true)
public class FreelancehuntSource implements JobSource {

    public static final String ID = "freelancehunt";

    private final JobRadarProperties.Freelancehunt config;
    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;

    public FreelancehuntSource(JobRadarProperties properties, HttpFetcher fetcher, ObjectMapper mapper) {
        this.config = properties.sources().freelancehunt();
        this.fetcher = fetcher;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Freelancehunt";
    }

    @Override
    public Duration pollInterval() {
        return config.pollInterval();
    }

    @Override
    public Optional<String> unavailableReason() {
        return config.token() == null || config.token().isBlank() ? Optional.of("FREELANCEHUNT_TOKEN is not set") : Optional.empty();
    }

    @Override
    public List<Job> fetch() {
        String filter = config.skillIds().isEmpty() ? ""
                : "?filter%5Bskill_id%5D=" + config.skillIds().stream().map(String::valueOf).collect(Collectors.joining(","));
        return parse(fetcher.get(config.baseUrl() + "/v2/projects" + filter, "application/json",
                Map.of("Authorization", "Bearer " + config.token().strip())));
    }

    List<Job> parse(String json) {
        JsonNode data;
        try {
            data = mapper.readTree(json).path("data");
        } catch (Exception e) {
            throw new SourceException("Freelancehunt returned invalid JSON", e);
        }
        List<Job> result = new ArrayList<>();
        for (JsonNode p : data) {
            JsonNode a = p.path("attributes");
            String id = p.path("id").asText("");
            String title = a.path("name").asText("");
            String url = p.path("links").path("self").path("web").asText("");
            if (id.isBlank() || title.isBlank() || url.isBlank()) {
                continue;
            }
            List<String> tags = new ArrayList<>();
            a.path("skills").forEach(s -> tags.add(s.path("name").asText()));
            if (a.path("is_remote_job").asBoolean(false)) {
                tags.add("remote");
            }
            JsonNode budget = a.path("budget");
            result.add(new Job(ID, id, title.strip(), a.path("employer").path("company").path("name").asText("").strip(), url, tags,
                    HtmlText.toPlain(a.path("description_html").asText(a.path("description").asText(""))), null,
                    postedAt(a.path("published_at").asText("")), Market.UA, JobType.FREELANCE_PROJECT,
                    budget.isObject() ? Budget.of(budget.path("amount").isNumber() ? budget.path("amount").decimalValue() : null, null,
                            budget.path("currency").asText(null), false) : null));
        }
        return result;
    }

    private static Instant postedAt(String value) {
        try {
            return value.isBlank() ? null : OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
