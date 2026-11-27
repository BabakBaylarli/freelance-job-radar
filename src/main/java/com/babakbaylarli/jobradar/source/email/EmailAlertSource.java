package com.babakbaylarli.jobradar.source.email;

import com.babakbaylarli.jobradar.ai.AiJobExtractor;
import com.babakbaylarli.jobradar.ai.ExtractedJob;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.source.HtmlText;
import com.babakbaylarli.jobradar.source.Job;
import com.babakbaylarli.jobradar.source.JobSource;
import com.babakbaylarli.jobradar.source.JobType;
import com.babakbaylarli.jobradar.source.Market;
import com.babakbaylarli.jobradar.source.SourceException;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.FlagTerm;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reads job alert e-mails over IMAP for platforms that have no public API (Upwork, Djinni, LinkedIn, Guru,
 * PeoplePerHour, Kwork, local boards...). Rules that keep it safe:
 * <ul>
 *   <li>it opens only the configured label/folder (default {@code JobAlerts}) - never the inbox or any other mail;</li>
 *   <li>it reads only unread messages, without marking them read while reading (IMAP PEEK);</li>
 *   <li>messages are marked read only after their jobs were stored ({@link #afterIngest()});</li>
 *   <li>message bodies are never logged - only counts and the sender domain.</li>
 * </ul>
 * A message from a known sender goes to its {@link AlertParser}; anything else (or a known sender whose layout changed)
 * goes to AI extraction when AI is enabled, otherwise becomes one job from subject + first link.
 */
@Component
@ConditionalOnProperty(name = "jobradar.sources.email-alerts.enabled", havingValue = "true")
public class EmailAlertSource implements JobSource {

    public static final String ID = "email";
    private static final Logger log = LoggerFactory.getLogger(EmailAlertSource.class);
    private static final List<String> NOISE_LINKS = List.of("unsubscribe", "preferences", "privacy", "settings", "help", "support", "mailto:");

    private final JobRadarProperties.EmailAlerts config;
    private final AiJobExtractor extractor;
    private final List<AlertParser> parsers = AlertParsers.all();

    private Store store;
    private Folder folder;
    private List<Message> toMarkRead = new ArrayList<>();

    public EmailAlertSource(JobRadarProperties properties, AiJobExtractor extractor) {
        this.config = properties.sources().emailAlerts();
        this.extractor = extractor;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Email alerts";
    }

    @Override
    public Duration pollInterval() {
        return config.pollInterval();
    }

    @Override
    public Optional<String> unavailableReason() {
        return config.credentialsPresent() ? Optional.empty() : Optional.of("MAIL_USERNAME / MAIL_APP_PASSWORD are not set");
    }

    @Override
    public synchronized List<Job> fetch() {
        closeQuietly();
        List<Job> jobs = new ArrayList<>();
        try {
            openFolder();
            Message[] unread = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            int limit = Math.min(unread.length, config.maxMessagesPerPoll());
            for (int i = 0; i < limit; i++) {
                if (!(unread[i] instanceof MimeMessage mime)) {
                    continue;
                }
                try {
                    EmailMessageData mail = EmailMessageData.from(mime);
                    List<Job> parsed = jobsFrom(mail);
                    log.info("event=email_parsed sender_domain={} jobs={}", mail.fromDomain(), parsed.size());
                    jobs.addAll(parsed);
                } catch (Exception e) {
                    log.warn("event=email_unparsed error={}", e.getClass().getSimpleName()); // never log the content
                }
                toMarkRead.add(unread[i]); // unparsable mail is marked read too, so it is not retried forever
            }
            return jobs;
        } catch (MessagingException e) {
            closeQuietly();
            throw new SourceException("IMAP error: " + e.getMessage(), e);
        }
    }

    /** Mark the processed e-mails as read, now that their jobs are stored. */
    @Override
    public synchronized void afterIngest() {
        try {
            for (Message message : toMarkRead) {
                message.setFlag(Flags.Flag.SEEN, true);
            }
            if (!toMarkRead.isEmpty()) {
                log.info("event=email_marked_read count={}", toMarkRead.size());
            }
        } catch (MessagingException e) {
            log.warn("event=email_mark_read_failed error={}", e.getMessage());
        } finally {
            closeQuietly();
        }
    }

    @jakarta.annotation.PreDestroy
    synchronized void closeQuietly() {
        try {
            if (folder != null && folder.isOpen()) {
                folder.close(false);
            }
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (MessagingException ignored) {
            // nothing useful to do while closing
        } finally {
            folder = null;
            store = null;
            toMarkRead = new ArrayList<>();
        }
    }

    private void openFolder() throws MessagingException {
        String protocol = config.ssl() ? "imaps" : "imap";
        Properties props = new Properties();
        props.put("mail." + protocol + ".peek", "true"); // reading must not mark the message as read
        props.put("mail." + protocol + ".connectiontimeout", "10000");
        props.put("mail." + protocol + ".timeout", "30000");
        store = Session.getInstance(props).getStore(protocol);
        store.connect(config.host(), config.port(), config.username(), config.appPassword());
        folder = store.getFolder(config.folder());
        if (!folder.exists()) {
            throw new SourceException("IMAP folder/label '%s' does not exist - create the label and the filter first (see README, 'Set up email alerts')"
                    .formatted(config.folder()));
        }
        folder.open(Folder.READ_WRITE);
    }

    // ---- turning one e-mail into jobs -------------------------------------------------------------------------

    List<Job> jobsFrom(EmailMessageData mail) {
        Optional<AlertParser> parser = parsers.stream().filter(p -> p.supports(mail)).findFirst();
        if (parser.isPresent()) {
            List<Job> jobs = parser.get().parse(mail);
            if (!jobs.isEmpty()) {
                return jobs;
            }
            log.info("event=email_parser_found_nothing platform={} - falling back", parser.get().platform());
        }
        return fallback(mail);
    }

    private List<Job> fallback(EmailMessageData mail) {
        String platform = platformOf(mail);
        Market market = marketOf(mail.fromDomain());
        JobType type = JobType.REMOTE_JOB;
        String text = mail.text() != null && !mail.text().isBlank() ? mail.text() : mail.html() == null ? "" : HtmlText.toPlain(mail.html());
        if (extractor.available()) {
            List<ExtractedJob> extracted = extractor.extract(mail.fromAddress(), mail.subject(), text, mail.links());
            if (!extracted.isEmpty()) {
                return extracted.stream().map(e -> new Job("email:" + platform, hash(e.url()), e.title(), e.company() == null ? "" : e.company(), e.url(),
                        List.of(), e.description() == null ? "" : e.description(), e.budget(), mail.received(), market, type, null)).toList();
            }
        }
        return firstJobLink(mail).map(link -> List.of(new Job("email:" + platform, hash(link), mail.subject().isBlank() ? "Job alert" : mail.subject(), "",
                link, List.of(), HtmlText.truncate(text, 1500), null, mail.received() == null ? Instant.now() : mail.received(), market, type, null)))
                .orElse(List.of());
    }

    private static Optional<String> firstJobLink(EmailMessageData mail) {
        if (mail.html() == null) {
            return Optional.empty();
        }
        for (Element a : Jsoup.parse(mail.html()).select("a[href]")) {
            String href = a.attr("href").strip();
            String lower = href.toLowerCase(Locale.ROOT);
            if ((lower.startsWith("http://") || lower.startsWith("https://")) && NOISE_LINKS.stream().noneMatch(lower::contains)) {
                return Optional.of(href);
            }
        }
        return Optional.empty();
    }

    /** {@code boss.az}, {@code djinni.co}: the sender's registrable-looking domain without a leading "mail."/"noreply." label. */
    static String platformOf(EmailMessageData mail) {
        String domain = mail.fromDomain();
        if (domain.isBlank()) {
            return "unknown";
        }
        String[] labels = domain.split("\\.");
        return labels.length <= 2 ? domain : labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    static Market marketOf(String domain) {
        if (domain.endsWith(".az")) {
            return Market.AZ;
        }
        if (domain.endsWith(".ua") || domain.endsWith("djinni.co")) {
            return Market.UA;
        }
        if (domain.endsWith(".ru")) {
            return Market.RU;
        }
        return Market.INTERNATIONAL;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 20);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
