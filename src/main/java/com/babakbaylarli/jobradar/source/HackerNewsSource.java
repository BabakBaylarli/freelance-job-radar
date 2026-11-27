package com.babakbaylarli.jobradar.source;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Hacker News "Ask HN: Who is hiring?" via the official HN Algolia API. Finds the newest thread posted by the
 * {@code whoishiring} account and turns every top-level comment into a job. No HTML pages are fetched; the link
 * back is the comment's own news.ycombinator.com item URL.
 */
@Component
@ConditionalOnProperty(name = "jobradar.sources.hacker-news.enabled", havingValue = "true", matchIfMissing = true)
public class HackerNewsSource implements JobSource {

    public static final String ID = "hackernews";
    private static final String ITEM_URL = "https://news.ycombinator.com/item?id=";
    private static final Pattern REMOTE = Pattern.compile("\\bremote\\b", Pattern.CASE_INSENSITIVE);
    private static final Logger log = LoggerFactory.getLogger(HackerNewsSource.class);

    private final JobRadarProperties.HackerNews config;
    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;

    public HackerNewsSource(JobRadarProperties properties, HttpFetcher fetcher, ObjectMapper mapper) {
        this.config = properties.sources().hackerNews();
        this.fetcher = fetcher;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Hacker News";
    }

    @Override
    public Duration pollInterval() {
        return config.pollInterval();
    }

    @Override
    public List<Job> fetch() {
        String searchUrl = config.baseUrl()
                + "/api/v1/search_by_date?tags=story,author_whoishiring&query=who%20is%20hiring&hitsPerPage=10";
        String threadId = findLatestThreadId(fetcher.get(searchUrl, "application/json"));
        log.info("Reading Hacker News hiring thread {}", threadId);
        return parseThread(fetcher.get(config.baseUrl() + "/api/v1/items/" + threadId, "application/json"));
    }

    String findLatestThreadId(String searchJson) {
        try {
            for (JsonNode hit : mapper.readTree(searchJson).path("hits")) {
                String title = hit.path("title").asText("").toLowerCase(Locale.ROOT);
                // The same account also posts "Who wants to be hired?" and "Freelancer?" threads.
                if (title.contains("who is hiring")) {
                    return hit.path("objectID").asText();
                }
            }
        } catch (Exception e) {
            throw new SourceException("Hacker News search returned invalid JSON", e);
        }
        throw new SourceException("No 'Who is hiring?' thread found on Hacker News");
    }

    List<Job> parseThread(String threadJson) {
        JsonNode children;
        try {
            children = mapper.readTree(threadJson).path("children");
        } catch (Exception e) {
            throw new SourceException("Hacker News thread returned invalid JSON", e);
        }
        List<Job> result = new ArrayList<>();
        for (JsonNode comment : children) {
            String html = comment.path("text").asText("");
            String id = comment.path("id").asText("");
            if (html.isBlank() || id.isBlank()) {
                continue; // deleted / flagged comments have no text
            }
            String text = HtmlText.toPlain(html);
            if (config.remoteOnly() && !REMOTE.matcher(text).find()) {
                continue;
            }
            String headline = HtmlText.firstLine(text, 160);
            result.add(new Job(
                    ID,
                    id,
                    headline,
                    companyFrom(headline),
                    ITEM_URL + id,
                    List.of(),
                    text,
                    null,
                    postedAt(comment)));
        }
        return result;
    }

    /** Hiring posts conventionally start with "Company | Role | Location | ...". */
    static String companyFrom(String headline) {
        String[] parts = headline.split("\\s*[|–—]\\s*", 2);
        String company = parts[0].strip();
        return parts.length > 1 && company.length() <= 60 ? company : "";
    }

    private static Instant postedAt(JsonNode comment) {
        long epoch = comment.path("created_at_i").asLong(0);
        if (epoch > 0) {
            return Instant.ofEpochSecond(epoch);
        }
        try {
            return Instant.parse(comment.path("created_at").asText(""));
        } catch (Exception e) {
            return null;
        }
    }
}
