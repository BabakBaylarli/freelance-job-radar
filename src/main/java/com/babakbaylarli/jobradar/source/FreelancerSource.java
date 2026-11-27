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
 * Freelancer.com Projects API ({@code /api/projects/0.1/projects/active/}). The endpoint answers without a token; if
 * {@code FREELANCER_TOKEN} is set it is sent as {@code freelancer-oauth-v1}. One request per configured query.
 * The project link is built from {@code seo_url}, so every message links back to the project on Freelancer.com.
 */
@Component
@ConditionalOnProperty(name = "jobradar.sources.freelancer.enabled", havingValue = "true", matchIfMissing = true)
public class FreelancerSource implements JobSource {

    public static final String ID = "freelancer";
    private static final Logger log = LoggerFactory.getLogger(FreelancerSource.class);

    private final JobRadarProperties.Freelancer config;
    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;

    public FreelancerSource(JobRadarProperties properties, HttpFetcher fetcher, ObjectMapper mapper) {
        this.config = properties.sources().freelancer();
        this.fetcher = fetcher;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Freelancer.com";
    }

    @Override
    public Duration pollInterval() {
        return config.pollInterval();
    }

    @Override
    public List<Job> fetch() {
        Map<String, String> headers = config.token() == null || config.token().isBlank()
                ? Map.of() : Map.of("freelancer-oauth-v1", config.token());
        Map<String, Job> unique = new LinkedHashMap<>();
        SourceException lastFailure = null;
        int failed = 0;
        for (String query : config.queries()) {
            String url = config.baseUrl() + "/api/projects/0.1/projects/active/?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&limit=" + config.limit() + "&full_description=true&job_details=true";
            try {
                for (Job job : parse(fetcher.get(url, "application/json", headers))) {
                    unique.putIfAbsent(job.externalId(), job);
                }
            } catch (SourceException e) {
                failed++;
                lastFailure = e;
                log.warn("Freelancer query '{}' failed: {}", query, e.getMessage());
            }
        }
        if (failed > 0 && failed == config.queries().size()) {
            throw lastFailure;
        }
        return new ArrayList<>(unique.values());
    }

    List<Job> parse(String json) {
        JsonNode projects;
        try {
            JsonNode root = mapper.readTree(json);
            if (!"success".equals(root.path("status").asText())) {
                throw new SourceException("Freelancer.com returned status '" + root.path("status").asText() + "': " + root.path("message").asText(""));
            }
            projects = root.path("result").path("projects");
        } catch (SourceException e) {
            throw e;
        } catch (Exception e) {
            throw new SourceException("Freelancer.com returned invalid JSON", e);
        }
        List<Job> result = new ArrayList<>();
        for (JsonNode p : projects) {
            String id = p.path("id").asText("");
            String title = p.path("title").asText("");
            String seo = p.path("seo_url").asText("");
            if (id.isBlank() || title.isBlank() || seo.isBlank() || "closed".equalsIgnoreCase(p.path("status").asText("active"))) {
                continue;
            }
            List<String> tags = new ArrayList<>();
            p.path("jobs").forEach(j -> tags.add(j.path("name").asText()));
            boolean hourly = "hourly".equalsIgnoreCase(p.path("type").asText());
            Budget budget = Budget.of(number(p.path("budget").path("minimum")), number(p.path("budget").path("maximum")),
                    p.path("currency").path("code").asText(null), hourly);
            long submitted = p.path("time_submitted").asLong(p.path("submitdate").asLong(0));
            result.add(new Job(ID, id, title.strip(), "", config.baseUrl() + "/projects/" + seo, tags,
                    HtmlText.toPlain(p.path("description").asText(p.path("preview_description").asText(""))), null,
                    submitted > 0 ? Instant.ofEpochSecond(submitted) : null, Market.INTERNATIONAL, JobType.FREELANCE_PROJECT, budget));
        }
        return result;
    }

    private static Number number(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }
}
