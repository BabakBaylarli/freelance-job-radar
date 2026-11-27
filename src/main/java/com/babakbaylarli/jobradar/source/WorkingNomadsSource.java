package com.babakbaylarli.jobradar.source;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Working Nomads public JSON feed ({@code /api/exposed_jobs/}). We keep their own job link and label messages "via Working Nomads". */
@Component
@ConditionalOnProperty(name = "jobradar.sources.working-nomads.enabled", havingValue = "true", matchIfMissing = true)
public class WorkingNomadsSource implements JobSource {

    public static final String ID = "workingnomads";
    private static final Pattern JOB_ID = Pattern.compile("/job/(?:go/)?(\\d+)");

    private final JobRadarProperties.WorkingNomads config;
    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;

    public WorkingNomadsSource(JobRadarProperties properties, HttpFetcher fetcher, ObjectMapper mapper) {
        this.config = properties.sources().workingNomads();
        this.fetcher = fetcher;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Working Nomads";
    }

    @Override
    public Duration pollInterval() {
        return config.pollInterval();
    }

    @Override
    public List<Job> fetch() {
        return parse(fetcher.get(config.baseUrl() + "/api/exposed_jobs/", "application/json"));
    }

    List<Job> parse(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new SourceException("Working Nomads returned invalid JSON", e);
        }
        if (!root.isArray()) {
            throw new SourceException("Working Nomads returned an unexpected payload (not an array)");
        }
        List<Job> result = new ArrayList<>();
        for (JsonNode j : root) {
            String url = j.path("url").asText("");
            String title = j.path("title").asText("");
            if (url.isBlank() || title.isBlank()) {
                continue;
            }
            Matcher id = JOB_ID.matcher(url);
            List<String> tags = new ArrayList<>();
            Arrays.stream(j.path("tags").asText("").split(",")).map(String::strip).filter(t -> !t.isEmpty()).forEach(tags::add);
            for (String field : List.of("category_name", "location")) {
                String value = j.path(field).asText("").strip();
                if (!value.isEmpty()) {
                    tags.add(value);
                }
            }
            result.add(new Job(ID, id.find() ? id.group(1) : url, title.strip(), j.path("company_name").asText("").strip(), url, tags,
                    HtmlText.toPlain(j.path("description").asText("")), null, postedAt(j.path("pub_date").asText("")),
                    Market.INTERNATIONAL, JobType.REMOTE_JOB, null));
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
