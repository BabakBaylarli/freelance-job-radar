package com.babakbaylarli.jobradar.bot;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.config.JobRadarProperties.Keyword;
import com.babakbaylarli.jobradar.apply.ApplicationService;
import com.babakbaylarli.jobradar.apply.ProposalSender;
import com.babakbaylarli.jobradar.persistence.ApplicationEntity;
import com.babakbaylarli.jobradar.persistence.ApplicationStatus;
import com.babakbaylarli.jobradar.persistence.JobEntity;
import com.babakbaylarli.jobradar.persistence.JobRepository.MarketStats;
import com.babakbaylarli.jobradar.persistence.JobRepository.SourceStats;
import com.babakbaylarli.jobradar.persistence.SourceStateEntity;
import com.babakbaylarli.jobradar.source.HtmlText;
import com.babakbaylarli.jobradar.source.Market;
import com.babakbaylarli.jobradar.source.SourceCatalog;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Builds all user-facing Telegram messages (Telegram HTML subset). Pure functions, easy to test. */
@Component
public class MessageFormatter {

    public static final int TELEGRAM_LIMIT = 4096;
    private static final int DIGEST_BUDGET = 3700;
    static final String DRAFT = "d";
    static final String SAVE = "s";
    static final String HIDE = "h";
    static final String MARKET = "m";
    // application flow (callback data "<action>:<application id>")
    static final String APP_SEND = "as";
    static final String APP_EDIT = "ae";
    static final String APP_REGENERATE = "ar";
    static final String APP_CANCEL = "ac";
    static final String APP_YES = "ay";
    static final String APP_NO = "an";
    static final String APP_COPY = "ak";

    public record DigestMessage(String html, Keyboard keyboard, List<JobEntity> included) {}

    // ---- single job -------------------------------------------------------------------------------------------

    public String jobCard(JobEntity job) {
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(escape(HtmlText.truncate(job.getTitle(), 200))).append("</b>\n");
        sb.append(job.getMarket().flag()).append(' ').append(job.getJobType().label()).append('\n');
        sb.append(companyLine(job)).append('\n');
        sb.append("🎯 Score ").append(job.getScore());
        if (!job.getMatchedKeywords().isEmpty()) {
            sb.append(" — ").append(escape(String.join(", ", job.getMatchedKeywords())));
        }
        sb.append('\n');
        if (job.getSalary() != null && !job.getSalary().isBlank()) {
            sb.append("💰 ").append(escape(job.getSalary())).append('\n');
        }
        String summary = shortDescription(job.getDescription(), 300);
        if (!summary.isEmpty()) {
            sb.append('\n').append(escape(summary)).append('\n');
        }
        sb.append("\n🔗 ").append(link(job.getUrl(), "View on " + sourceName(job.getSource())));
        job.getAlsoOn().forEach(entry -> {
            int bar = entry.indexOf('|');
            if (bar > 0) {
                sb.append("\n🔗 ").append(link(entry.substring(bar + 1), "Also on " + sourceName(entry.substring(0, bar))));
            }
        });
        return sb.toString();
    }

    public Keyboard jobKeyboard(JobEntity job, boolean saved) {
        long id = job.getId();
        return new Keyboard(List.of(
                List.of(new Button("✍️ Draft proposal", DRAFT + ":" + id)),
                List.of(new Button(saved ? "✅ Saved" : "⭐ Save", SAVE + ":" + id), new Button("🙈 Hide", HIDE + ":" + id))));
    }

    public String proposal(JobEntity job, String text) {
        String header = "✍️ <b>Proposal draft</b> — %s%s\n(tap the text to copy, review it before sending)\n\n".formatted(
                escape(HtmlText.truncate(job.getTitle(), 120)), job.getCompany().isBlank() ? "" : " · " + escape(job.getCompany()));
        String footer = "</pre>\n🔗 " + link(job.getUrl(), "Open the job to apply");
        int budget = TELEGRAM_LIMIT - header.length() - footer.length() - "<pre>".length() - 50;
        return header + "<pre>" + escapedToFit(text, budget) + footer;
    }

    /** Escape {@code text} and shorten it until the <em>escaped</em> form (which can be longer) fits {@code budget}. */
    static String escapedToFit(String text, int budget) {
        String raw = HtmlText.truncate(text, budget);
        String escaped = escape(raw);
        while (escaped.length() > budget && raw.length() > 1) {
            raw = HtmlText.truncate(raw, Math.max(1, raw.length() - (escaped.length() - budget) - 1));
            escaped = escape(raw);
        }
        return escaped;
    }

    // ---- one-tap apply -----------------------------------------------------------------------------------------

    public String draft(ApplicationEntity app, JobEntity job, Optional<ProposalSender> route, Optional<String> missingSetup) {
        StringBuilder sb = new StringBuilder("✍️ <b>Draft</b> — ").append(escape(HtmlText.truncate(job.getTitle(), 110)));
        if (!job.getCompany().isBlank()) {
            sb.append(" · ").append(escape(job.getCompany()));
        }
        sb.append("\n");
        if (route.isPresent()) {
            sb.append("🚀 Route: ").append(escape(route.get().route())).append(" — sent <b>only after you approve</b>\n");
            if (app.getRecipient() != null) {
                sb.append("📨 To: ").append(escape(app.getRecipient())).append('\n');
            }
        } else {
            sb.append("📋 No official way to send this automatically — use <b>Copy &amp; open</b> and submit it yourself.\n");
            missingSetup.ifPresent(reason -> sb.append("ℹ️ To enable sending here: ").append(escape(reason)).append('\n'));
        }
        if (app.getBidAmount() != null) {
            sb.append("💰 Bid: ").append(app.getBidAmount().stripTrailingZeros().toPlainString()).append(' ')
                    .append(app.getBidCurrency() == null ? "" : escape(app.getBidCurrency())).append(" in ")
                    .append(app.getBidDays() == null ? "?" : app.getBidDays()).append(" days  (change: reply <code>bid 650 5</code> after ✏️ Edit)\n");
        }
        String footer = "\n" + (route.isPresent() ? "Reply <b>ok</b> to this message, or tap ✅ Send, to send it." : "Tap 📋 Copy &amp; open when you are happy with it.")
                + "\n🔗 " + link(job.getUrl(), "Open the job");
        int budget = TELEGRAM_LIMIT - sb.length() - footer.length() - "<pre></pre>".length() - 80;
        return sb.append("<pre>").append(escapedToFit(app.getText(), budget)).append("</pre>").append(footer).toString();
    }

    public Keyboard draftKeyboard(ApplicationEntity app, boolean canSend) {
        long id = app.getId();
        List<Button> first = canSend
                ? List.of(new Button("✅ Send", APP_SEND + ":" + id), new Button("✏️ Edit", APP_EDIT + ":" + id))
                : List.of(new Button("📋 Copy & open", APP_COPY + ":" + id), new Button("✏️ Edit", APP_EDIT + ":" + id));
        return new Keyboard(List.of(first, List.of(new Button("🔁 Regenerate", APP_REGENERATE + ":" + id), new Button("❌ Cancel", APP_CANCEL + ":" + id))));
    }

    public String editPrompt() {
        return "✏️ <b>Edit mode.</b> Send me your next message:\n• the full edited text, or\n• an instruction such as <i>shorter, mention Kafka</i> (needs AI), or\n"
                + "• <code>bid 650 5</code> to change the bid amount and days.\nNothing is sent until you approve the new draft.";
    }

    public String confirmation(ApplicationEntity app, String note) {
        return "⚠️ " + escape(note) + "\n<b>Send application #" + app.getId() + " now?</b> Tap ✅ or reply <b>ok</b> to this message.";
    }

    public Keyboard confirmationKeyboard(ApplicationEntity app) {
        return new Keyboard(List.of(List.of(new Button("✅ Yes, send", APP_YES + ":" + app.getId()), new Button("❌ No", APP_NO + ":" + app.getId()))));
    }

    public String sent(ApplicationEntity app, JobEntity job, Optional<ProposalSender> route) {
        return "✅ <b>Sent</b> via " + escape(route.map(ProposalSender::route).orElse(app.getPlatform()))
                + (app.getRecipient() == null ? "" : " to " + escape(app.getRecipient()))
                + (app.getResponseId() == null || app.getResponseId().isBlank() ? "" : "\nReference: <code>" + escape(app.getResponseId()) + "</code>")
                + "\n" + escape(HtmlText.truncate(job.getTitle(), 110)) + "\n🔗 " + link(job.getUrl(), "Open the job");
    }

    public String failed(ApplicationEntity app, String reason) {
        return "❌ <b>Not sent.</b> " + escape(reason) + "\nNothing was retried automatically. You can retry, or copy the text and apply yourself.";
    }

    public Keyboard failedKeyboard(ApplicationEntity app) {
        long id = app.getId();
        return new Keyboard(List.of(List.of(new Button("🔁 Retry", APP_SEND + ":" + id), new Button("📋 Copy & open", APP_COPY + ":" + id)),
                List.of(new Button("❌ Cancel", APP_CANCEL + ":" + id))));
    }

    public String blocked(String reason) {
        return "⏸ <b>Not sent.</b> " + escape(reason);
    }

    /** The proposal as one copy-friendly message plus the job link (tap the text to copy it). */
    public String copyReady(ApplicationEntity app, JobEntity job) {
        String footer = "\n🔗 " + link(job.getUrl(), "Open the job and paste it there");
        String header = "📋 <b>Copy &amp; open</b> — tap the text to copy it, then apply yourself:\n";
        int budget = TELEGRAM_LIMIT - header.length() - footer.length() - "<pre></pre>".length() - 80;
        String bid = app.getBidAmount() == null ? "" : "\n💰 Suggested bid: " + app.getBidAmount().stripTrailingZeros().toPlainString() + " "
                + (app.getBidCurrency() == null ? "" : escape(app.getBidCurrency())) + (app.getBidDays() == null ? "" : " in " + app.getBidDays() + " days");
        return header + "<pre>" + escapedToFit(app.getText(), budget - bid.length()) + "</pre>" + bid + footer;
    }

    public String applied(List<ApplicationEntity> applications, java.util.function.Function<Long, Optional<JobEntity>> jobs) {
        if (applications.isEmpty()) {
            return "📨 No applications yet. Tap ✍️ Draft proposal under a job to start one.";
        }
        StringBuilder sb = new StringBuilder("📨 <b>Last applications</b>\n");
        for (ApplicationEntity app : applications) {
            Optional<JobEntity> job = jobs.apply(app.getJobId());
            String line = "\n%s <b>%s</b> · %s · %s".formatted(statusIcon(app.getStatus()), escape(job.map(j -> HtmlText.truncate(j.getTitle(), 70)).orElse("(job removed)")),
                    app.getStatus().name().toLowerCase(), escape(app.getPlatform()));
            if (sb.length() + line.length() > DIGEST_BUDGET) {
                break;
            }
            sb.append(line);
            job.ifPresent(j -> sb.append("\n   ").append(link(j.getUrl(), "job")));
        }
        return sb.toString();
    }

    public String limits(ApplicationService.LimitsView view, Instant now) {
        StringBuilder sb = new StringBuilder("🚦 <b>Today's sending limits</b>\nTotal: <b>%d / %d</b> sent by the bot".formatted(view.sentToday(), view.dailyLimit()));
        view.sentPerPlatform().forEach((platform, sent) -> sb.append("\n• ").append(escape(platform)).append(": ").append(sent)
                .append(view.platformLimits().containsKey(platform) ? " / " + view.platformLimits().get(platform) : ""));
        view.platformLimits().forEach((platform, limit) -> {
            if (!view.sentPerPlatform().containsKey(platform)) {
                sb.append("\n• ").append(escape(platform)).append(": 0 / ").append(limit);
            }
        });
        sb.append("\nMinimum gap between sends: ").append(humanize(view.minGap()));
        view.nextAllowed().ifPresent(next -> sb.append("\n⏳ Next send possible in ").append(humanize(Duration.between(now, next))));
        sb.append("\n\nCopy &amp; open does not count: only applications the bot itself submitted.");
        return sb.toString();
    }

    private static String statusIcon(ApplicationStatus status) {
        return switch (status) {
            case SENT -> "✅";
            case FAILED -> "❌";
            case COPIED -> "📋";
            case CANCELLED -> "🚫";
            default -> "📝";
        };
    }

    // ---- digest -----------------------------------------------------------------------------------------------

    public DigestMessage digest(List<JobEntity> jobs, long remaining, String title) {
        StringBuilder sb = new StringBuilder("📬 <b>").append(escape(title)).append("</b>\n");
        List<JobEntity> included = new ArrayList<>();
        for (JobEntity job : jobs) {
            String entry = digestEntry(included.size() + 1, job);
            if (sb.length() + entry.length() > DIGEST_BUDGET) {
                break;
            }
            sb.append('\n').append(entry);
            included.add(job);
        }
        long more = remaining + (jobs.size() - included.size());
        if (more > 0) {
            sb.append("\n\n…and ").append(more).append(" more waiting — send /digest to see the next ones.");
        }
        List<List<Button>> rows = new ArrayList<>();
        for (int i = 0; i < included.size(); i++) {
            long id = included.get(i).getId();
            int n = i + 1;
            rows.add(List.of(
                    new Button("✍️ " + n, DRAFT + ":" + id),
                    new Button("⭐ " + n, SAVE + ":" + id),
                    new Button("🙈 " + n, HIDE + ":" + id)));
        }
        return new DigestMessage(sb.toString(), new Keyboard(rows), included);
    }

    private String digestEntry(int number, JobEntity job) {
        StringBuilder sb = new StringBuilder();
        sb.append(number).append(". ").append(job.getMarket().flag()).append(" <b>").append(escape(HtmlText.truncate(job.getTitle(), 110))).append("</b>\n");
        sb.append("   ").append(companyLine(job)).append(" · score ").append(job.getScore()).append('\n');
        if (!job.getMatchedKeywords().isEmpty()) {
            sb.append("   🎯 ").append(escape(String.join(", ", job.getMatchedKeywords()))).append('\n');
        }
        sb.append("   🔗 ").append(link(job.getUrl(), "View on " + sourceName(job.getSource())));
        job.getAlsoOn().forEach(entry -> {
            int bar = entry.indexOf('|');
            if (bar > 0) {
                sb.append(" · ").append(link(entry.substring(bar + 1), sourceName(entry.substring(0, bar))));
            }
        });
        return sb.append('\n').toString();
    }

    // ---- lists and info ---------------------------------------------------------------------------------------

    public String saved(List<JobEntity> jobs) {
        if (jobs.isEmpty()) {
            return "⭐ No saved jobs yet. Tap <b>⭐ Save</b> under a job to keep it here.";
        }
        StringBuilder sb = new StringBuilder("⭐ <b>Saved jobs</b> (").append(jobs.size()).append(")\n");
        for (JobEntity job : jobs) {
            String line = "\n• <b>%s</b>%s · score %d\n  %s".formatted(escape(HtmlText.truncate(job.getTitle(), 100)),
                    job.getCompany().isBlank() ? "" : " — " + escape(job.getCompany()), job.getScore(),
                    link(job.getUrl(), "View on " + sourceName(job.getSource())));
            if (sb.length() + line.length() > DIGEST_BUDGET) {
                sb.append("\n\n…more saved jobs not shown.");
                break;
            }
            sb.append(line);
        }
        return sb.toString();
    }

    public String stats(List<String> sourceIds, List<SourceStats> stats, List<MarketStats> markets,
            Map<String, SourceStateEntity> states, Instant now) {
        Map<String, SourceStats> byId = stats.stream().collect(Collectors.toMap(SourceStats::getSource, s -> s));
        List<String> ids = new ArrayList<>(sourceIds);
        byId.keySet().stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        if (ids.isEmpty()) {
            return "📊 No sources are enabled.";
        }
        ids.sort(java.util.Comparator.naturalOrder());
        StringBuilder sb = new StringBuilder("📊 <b>Last 7 days</b>\n");
        long totalSeen = 0;
        long totalMatched = 0;
        for (String id : ids) {
            SourceStats s = byId.get(id);
            long seen = s == null ? 0 : s.getSeen();
            long matched = s == null ? 0 : s.getMatched();
            totalSeen += seen;
            totalMatched += matched;
            sb.append("\n• <b>").append(escape(sourceName(id))).append("</b> — seen ").append(seen)
                    .append(", matched ").append(matched).append('\n').append("   ").append(pollStatus(stateFor(states, id), now));
        }
        sb.append("\n\nTotal: seen ").append(totalSeen).append(", matched ").append(totalMatched);
        if (!markets.isEmpty()) {
            sb.append("\n\n<b>By market</b>\n").append(markets.stream()
                    .map(m -> m.getMarket().flag() + " seen " + m.getSeen() + ", matched " + m.getMatched())
                    .collect(Collectors.joining("\n")));
        }
        return sb.toString();
    }

    /** Sub-sources such as {@code email:upwork} share the poll state of their parent source ({@code email}). */
    private static SourceStateEntity stateFor(Map<String, SourceStateEntity> states, String id) {
        SourceStateEntity own = states.get(id);
        int colon = id.indexOf(':');
        return own != null || colon < 0 ? own : states.get(id.substring(0, colon));
    }

    private static String pollStatus(SourceStateEntity state, Instant now) {
        if (state == null || state.getLastPolledAt() == null) {
            return "not polled yet";
        }
        String ago = humanize(Duration.between(state.getLastPolledAt(), now)) + " ago";
        return "FAILED".equals(state.getLastStatus())
                ? "⚠️ last poll failed " + ago + (state.getLastError() == null ? "" : ": " + escape(HtmlText.truncate(state.getLastError(), 100)))
                : "✅ polled " + ago;
    }

    public String keywords(JobRadarProperties properties, List<String> enabledSources) {
        JobRadarProperties.Matching m = properties.matching();
        StringBuilder sb = new StringBuilder("🎯 <b>Matching config</b>\n");
        sb.append("\nMinimum score: <b>").append(m.minScore()).append("</b>\n");
        sb.append("\n<b>Include</b> (weight)\n");
        if (m.include().isEmpty()) {
            sb.append("<i>none configured — nothing can match. Edit config/keywords.yml</i>\n");
        } else {
            sb.append(m.include().stream()
                    .sorted(Comparator.comparingInt(Keyword::weight).reversed().thenComparing(Keyword::term))
                    .map(k -> escape(k.term()) + " " + k.weight())
                    .collect(Collectors.joining(" · "))).append('\n');
        }
        sb.append("\n<b>Exclude</b>\n");
        sb.append(m.exclude().isEmpty() ? "<i>none</i>" : escape(String.join(" · ", m.exclude()))).append('\n');
        JobRadarProperties.Notifications n = properties.notifications();
        sb.append("\n<b>Notifications</b>\nmax ").append(n.maxPerHour()).append("/hour, quiet hours: ")
                .append(escape(n.quietHours().describe())).append('\n');
        sb.append("\n<b>Sources</b>\n").append(enabledSources.isEmpty() ? "<i>none</i>" : escape(String.join(", ", enabledSources)));
        return sb.toString();
    }

    public String sources(List<SourceCatalog.Entry> entries, Map<String, SourceStateEntity> states,
            Map<String, Long> last24h, Instant now) {
        StringBuilder sb = new StringBuilder("🔌 <b>Sources</b>\n");
        for (SourceCatalog.Entry entry : entries) {
            sb.append("\n<b>").append(escape(entry.displayName())).append("</b> — ");
            switch (entry.status()) {
                case DISABLED -> sb.append("⚪ off (disabled in config)\n");
                case SKIPPED -> sb.append("🟡 skipped: ").append(escape(entry.reason())).append('\n');
                case ACTIVE -> {
                    SourceStateEntity state = states.get(entry.id());
                    sb.append("🟢 on");
                    if (state == null || state.getLastPolledAt() == null) {
                        sb.append(" · not polled yet\n");
                    } else {
                        sb.append(" · last success ").append(state.getLastSuccessAt() == null ? "never"
                                : humanize(Duration.between(state.getLastSuccessAt(), now)) + " ago").append('\n');
                        if ("FAILED".equals(state.getLastStatus())) {
                            sb.append("   ⚠️ last error: ").append(escape(HtmlText.truncate(String.valueOf(state.getLastError()), 120))).append('\n');
                        }
                    }
                    sb.append("   jobs in last 24h: ").append(last24h.getOrDefault(entry.id(), 0L)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    public String markets(Set<Market> enabled) {
        return "🗺 <b>Markets</b>\nTap a market to switch it on or off. Jobs from switched-off markets are still collected "
                + "but not sent.\n\nOn: " + (enabled.isEmpty() ? "<i>none</i>" : enabled.stream()
                .map(m -> m.flag() + " " + m.label()).collect(Collectors.joining(", ")));
    }

    public Keyboard marketsKeyboard(Set<Market> enabled) {
        List<List<Button>> rows = new ArrayList<>();
        for (Market market : Market.values()) {
            rows.add(List.of(new Button((enabled.contains(market) ? "✅ " : "⬜ ") + market.flag() + " " + market.label(),
                    MARKET + ":" + market.name())));
        }
        return new Keyboard(rows);
    }

    public String welcome(boolean paused, boolean aiEnabled) {
        return "👋 <b>Freelance Job Radar</b>\nI watch official remote-job feeds, send you the jobs that match your skills "
                + "and draft tailored proposals on demand.\n\n"
                + (paused ? "⏸ Notifications are currently <b>paused</b> (/resume).\n" : "")
                + (aiEnabled ? "" : "ℹ️ AI proposals are disabled (no ANTHROPIC_API_KEY) — everything else works.\n")
                + "\n" + help();
    }

    public String help() {
        return """
                <b>Commands</b>
                /digest — top 10 unseen matches right now
                /sources — every source: on/off, last success, errors
                /markets — switch 🌍 🇷🇺 🇺🇦 🇦🇿 on or off
                /saved — jobs you saved
                /stats — jobs seen / matched per source (7 days)
                /keywords — current matching config
                /applied — your last applications and their status
                /limits — today's sending limits
                /pause · /resume — stop / start notifications
                /help — this message

                Under each job: ✍️ draft a proposal · ⭐ save · 🙈 hide.""";
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private String companyLine(JobEntity job) {
        String company = job.getCompany().isBlank() ? "" : "🏢 " + escape(HtmlText.truncate(job.getCompany(), 80)) + " · ";
        return company + "via " + escape(sourceName(job.getSource()));
    }

    static String shortDescription(String description, int max) {
        return HtmlText.truncate(description.replaceAll("\\s+", " ").strip(), max);
    }

    static String link(String url, String label) {
        return "<a href=\"" + url.replace("&", "&amp;").replace("\"", "&quot;") + "\">" + escape(label) + "</a>";
    }

    /** Escapes only what Telegram's HTML mode requires (named entities beyond these four are not supported). */
    public static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static String sourceName(String id) {
        return SourceCatalog.displayName(id);
    }

    static String humanize(Duration duration) {
        long minutes = Math.max(0, duration.toMinutes());
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + "m";
        }
        if (minutes < 60 * 48) {
            return (minutes / 60) + "h";
        }
        return (minutes / (60 * 24)) + "d";
    }
}
