package com.babakbaylarli.jobradar.source;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RemoteOK public JSON API ({@code /api}).
 * <p>
 * Terms: attribution and a link back to the job are required. We therefore keep RemoteOK's own job
 * {@code url} (never the {@code apply_url}) and the bot shows "via RemoteOK" with every message. The first
 * array element of the response is a legal notice object, not a job, and is skipped.
 */
@Component
@ConditionalOnProperty(name = "jobradar.sources.remote-ok.enabled", havingValue = "true", matchIfMissing = true)
public class RemoteOkSource implements JobSource {

    public static final String ID = "remoteok";
    private static final Logger log = LoggerFactory.getLogger(RemoteOkSource.class);

    private final JobRadarProperties.RemoteOk config;
    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;

    public RemoteOkSource(JobRadarProperties properties, HttpFetcher fetcher, ObjectMapper mapper) {
        this.config = properties.sources().remoteOk();
        this.fetcher = fetcher;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "RemoteOK";
    }

    @Override
    public Duration pollInterval() {
        return config.pollInterval();
    }

    @Override
    public List<Job> fetch() {
        return parse(fetcher.get(config.baseUrl() + "/api", "application/json"));
    }

    List<Job> parse(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new SourceException("RemoteOK returned invalid JSON", e);
        }
        if (!root.isArray()) {
            throw new SourceException("RemoteOK returned an unexpected payload (not an array)");
        }
        List<Job> result = new ArrayList<>();
        for (JsonNode node : root) {
            String id = node.path("id").asText("");
            String url = node.path("url").asText("");
            String title = node.path("position").asText("");
            if (id.isBlank() || url.isBlank() || title.isBlank()) {
                continue; // legal notice element or malformed entry
            }
            List<String> tags = new ArrayList<>();
            node.path("tags").forEach(t -> tags.add(t.asText()));
            String location = node.path("location").asText("");
            if (!location.isBlank()) {
                tags.add(location);
            }
            result.add(new Job(
                    ID,
                    id,
                    title.strip(),
                    node.path("company").asText("").strip(),
                    url,
                    tags,
                    HtmlText.toPlain(node.path("description").asText("")),
                    salary(node.path("salary_min").asLong(0), node.path("salary_max").asLong(0)),
                    postedAt(node)));
        }
        log.debug("Parsed {} RemoteOK jobs", result.size());
        return result;
    }

    private static String salary(long min, long max) {
        NumberFormat fmt = NumberFormat.getIntegerInstance(Locale.US);
        if (min > 0 && max > 0) {
            return "$%s - $%s".formatted(fmt.format(min), fmt.format(max));
        }
        if (max > 0 || min > 0) {
            return "$" + fmt.format(Math.max(min, max));
        }
        return null;
    }

    private static Instant postedAt(JsonNode node) {
        try {
            String date = node.path("date").asText("");
            if (!date.isBlank()) {
                return OffsetDateTime.parse(date).toInstant();
            }
        } catch (Exception ignored) {
            // fall through to epoch
        }
        long epoch = node.path("epoch").asLong(0);
        return epoch > 0 ? Instant.ofEpochSecond(epoch) : null;
    }
}
