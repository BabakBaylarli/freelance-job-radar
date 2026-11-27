package com.babakbaylarli.jobradar.source.email;

import com.babakbaylarli.jobradar.source.Budget;
import com.babakbaylarli.jobradar.source.HtmlText;
import com.babakbaylarli.jobradar.source.Job;
import com.babakbaylarli.jobradar.source.JobType;
import com.babakbaylarli.jobradar.source.Market;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Parser for the common shape of job alert e-mails: a list of jobs where every job has a link to its page on the
 * platform. It finds those links, takes the link text as the title and the surrounding block as description, and reads
 * a budget out of that block. Per-platform behaviour is only configuration (sender, link pattern, market, type).
 * <p>
 * These parsers are heuristics: platforms change their e-mail layout without notice. When a parser finds nothing, the
 * e-mail falls back to AI extraction (if enabled) or to "subject + first link", so an alert is never silently lost.
 */
public final class LinkListAlertParser implements AlertParser {

    public record Config(String platform, List<String> senderDomains, Pattern linkPattern, String urlTemplate, Market market, JobType type,
            String defaultCurrency, boolean companyOnLineAfterTitle) {}

    private static final Set<String> GENERIC_LINK_TEXT = Set.of("view job", "view", "apply", "apply now", "see job", "see details", "view details",
            "view all jobs", "see all jobs", "more jobs", "open", "read more", "посмотреть", "подробнее", "детальніше", "ətraflı");
    private static final Pattern MONEY_PREFIX = Pattern.compile("(?<cur>[$€£₽₴₼])\\s?(?<a>\\d[\\d,.\\s\\u00a0]*\\d|\\d)(?:\\s*[-–—]\\s*[$€£₽₴₼]?\\s?(?<b>\\d[\\d,.\\s\\u00a0]*\\d|\\d))?");
    private static final Pattern MONEY_SUFFIX = Pattern.compile("(?<a>\\d[\\d,.\\s\\u00a0]*\\d|\\d)(?:\\s*[-–—]\\s*(?<b>\\d[\\d,.\\s\\u00a0]*\\d|\\d))?\\s*(?<cur>₽|руб\\.?|грн\\.?|₴|₼|AZN|USD|EUR|GBP|RUB|UAH)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOURLY = Pattern.compile("(/\\s?h(ou)?r|hourly|per hour|в час|за годину|saatlıq)", Pattern.CASE_INSENSITIVE);

    private final Config config;

    public LinkListAlertParser(Config config) {
        this.config = config;
    }

    @Override
    public String platform() {
        return config.platform();
    }

    @Override
    public boolean supports(EmailMessageData mail) {
        return config.senderDomains().stream().anyMatch(d -> mail.fromDomain().equals(d) || mail.fromDomain().endsWith("." + d));
    }

    @Override
    public List<Job> parse(EmailMessageData mail) {
        if (mail.html() == null || mail.html().isBlank()) {
            return List.of();
        }
        Document doc = Jsoup.parse(mail.html());
        Map<String, List<Element>> anchorsById = new LinkedHashMap<>();
        for (Element a : doc.select("a[href]")) {
            String id = jobId(a.attr("href"));
            if (id != null) {
                anchorsById.computeIfAbsent(id, k -> new ArrayList<>()).add(a);
            }
        }
        List<Job> jobs = new ArrayList<>();
        for (Map.Entry<String, List<Element>> entry : anchorsById.entrySet()) {
            Element titleAnchor = bestTitleAnchor(entry.getValue());
            if (titleAnchor == null) {
                continue;
            }
            String title = clean(titleAnchor.text());
            List<String> lines = blockLines(titleAnchor, title);
            String company = config.companyOnLineAfterTitle() && !lines.isEmpty() && !looksLikeMoney(lines.get(0)) ? truncate(lines.get(0), 80) : "";
            String description = String.join("\n", lines);
            Budget budget = budget(title + "\n" + description);
            jobs.add(new Job("email:" + config.platform(), entry.getKey(), title, company, config.urlTemplate().formatted(entry.getKey()), List.of(),
                    truncate(description, 1500), null, mail.received() == null ? Instant.now() : mail.received(), config.market(), config.type(), budget));
        }
        return jobs;
    }

    /** Job id from a link; also finds links wrapped in tracking redirects by decoding the URL once. */
    private String jobId(String href) {
        for (String candidate : List.of(href, safeDecode(href))) {
            Matcher m = config.linkPattern().matcher(candidate);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static Element bestTitleAnchor(List<Element> anchors) {
        Element best = null;
        for (Element a : anchors) {
            String text = clean(a.text());
            if (text.length() < 5 || GENERIC_LINK_TEXT.contains(text.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (best == null || text.length() > clean(best.text()).length()) {
                best = a;
            }
        }
        return best;
    }

    /** Text lines of the smallest enclosing block that contains only this job's link, without the title itself. */
    private List<String> blockLines(Element titleAnchor, String title) {
        Element block = titleAnchor;
        for (Element parent = titleAnchor.parent(); parent != null && !"body".equals(parent.tagName()) && !"html".equals(parent.tagName()); parent = parent.parent()) {
            if (distinctJobIds(parent) > 1) {
                break;
            }
            block = parent;
        }
        List<String> lines = new ArrayList<>();
        for (String line : HtmlText.toPlain(block.outerHtml()).split("\n")) {
            String cleaned = clean(line);
            if (!cleaned.isEmpty() && !cleaned.equals(title) && !GENERIC_LINK_TEXT.contains(cleaned.toLowerCase(Locale.ROOT))) {
                lines.add(cleaned);
            }
        }
        return lines;
    }

    private int distinctJobIds(Element element) {
        Set<String> ids = new java.util.HashSet<>();
        for (Element a : element.select("a[href]")) {
            String id = jobId(a.attr("href"));
            if (id != null) {
                ids.add(id);
            }
        }
        return ids.size();
    }

    // ---- budget -------------------------------------------------------------------------------------------------

    Budget budget(String text) {
        boolean hourly = HOURLY.matcher(text).find();
        Matcher prefix = MONEY_PREFIX.matcher(text);
        if (prefix.find()) {
            return Budget.of(amount(prefix.group("a")), amount(prefix.group("b")), currencyOf(prefix.group("cur")), hourly);
        }
        Matcher suffix = MONEY_SUFFIX.matcher(text);
        if (suffix.find()) {
            return Budget.of(amount(suffix.group("a")), amount(suffix.group("b")), currencyOf(suffix.group("cur")), hourly);
        }
        return null;
    }

    private String currencyOf(String symbol) {
        String s = symbol.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "$" -> "USD";
            case "€", "eur" -> "EUR";
            case "£", "gbp" -> "GBP";
            case "₽", "rub", "руб", "руб." -> "RUB";
            case "₴", "грн", "грн.", "uah" -> "UAH";
            case "₼", "azn" -> "AZN";
            case "usd" -> "USD";
            default -> config.defaultCurrency();
        };
    }

    private static BigDecimal amount(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[\\s\\u00a0,]", "");
        try {
            return digits.isEmpty() ? null : new BigDecimal(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean looksLikeMoney(String line) {
        return MONEY_PREFIX.matcher(line).find() || MONEY_SUFFIX.matcher(line).find();
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace(' ', ' ').replaceAll("\\s+", " ").strip();
    }

    private static String truncate(String text, int max) {
        return HtmlText.truncate(text, max);
    }

    private static String safeDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
