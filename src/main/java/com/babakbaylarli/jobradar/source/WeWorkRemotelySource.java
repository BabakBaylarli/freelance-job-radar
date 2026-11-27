package com.babakbaylarli.jobradar.source;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * We Work Remotely public RSS feeds (programming / back-end categories). One request per configured feed;
 * a failing feed does not prevent the others from being read.
 */
@Component
@ConditionalOnProperty(name = "jobradar.sources.we-work-remotely.enabled", havingValue = "true", matchIfMissing = true)
public class WeWorkRemotelySource implements JobSource {

    public static final String ID = "weworkremotely";
    private static final Logger log = LoggerFactory.getLogger(WeWorkRemotelySource.class);

    private final JobRadarProperties.WeWorkRemotely config;
    private final HttpFetcher fetcher;

    public WeWorkRemotelySource(JobRadarProperties properties, HttpFetcher fetcher) {
        this.config = properties.sources().weWorkRemotely();
        this.fetcher = fetcher;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "We Work Remotely";
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
        for (String feed : config.feeds()) {
            try {
                for (Job job : parse(fetcher.get(config.baseUrl() + feed, "application/rss+xml, application/xml"))) {
                    unique.putIfAbsent(job.externalId(), job);
                }
            } catch (SourceException e) {
                failed++;
                lastFailure = e;
                log.warn("WWR feed {} failed: {}", feed, e.getMessage());
            }
        }
        if (failed == config.feeds().size() && lastFailure != null) {
            throw lastFailure;
        }
        return new ArrayList<>(unique.values());
    }

    List<Job> parse(String xml) {
        NodeList items;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // no XXE
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            items = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)))
                    .getElementsByTagName("item");
        } catch (Exception e) {
            throw new SourceException("We Work Remotely returned invalid RSS", e);
        }
        List<Job> result = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String link = text(item, "link");
            String guid = text(item, "guid");
            String rawTitle = text(item, "title");
            if (link.isBlank() || rawTitle.isBlank()) {
                continue;
            }
            // WWR titles look like "Company: Job title"
            String company = "";
            String title = rawTitle;
            int sep = rawTitle.indexOf(": ");
            if (sep > 0) {
                company = rawTitle.substring(0, sep).strip();
                title = rawTitle.substring(sep + 2).strip();
            }
            List<String> tags = new ArrayList<>();
            Arrays.stream(text(item, "skills").split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(tags::add);
            for (String field : List.of("category", "type", "region")) {
                String value = text(item, field);
                if (!value.isBlank()) {
                    tags.add(value);
                }
            }
            result.add(new Job(
                    ID,
                    guid.isBlank() ? link : guid,
                    title,
                    company,
                    link,
                    tags,
                    HtmlText.toPlain(text(item, "description")),
                    null,
                    parseDate(text(item, "pubDate"))));
        }
        return result;
    }

    private static String text(Element parent, String tag) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element el && el.getTagName().equals(tag)) {
                return el.getTextContent().strip();
            }
        }
        return "";
    }

    private static Instant parseDate(String value) {
        try {
            return value.isBlank() ? null : ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
