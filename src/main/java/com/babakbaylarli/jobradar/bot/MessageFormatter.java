package com.babakbaylarli.jobradar.bot;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.config.JobRadarProperties.Keyword;
import com.babakbaylarli.jobradar.persistence.JobEntity;
import com.babakbaylarli.jobradar.persistence.JobRepository.SourceStats;
import com.babakbaylarli.jobradar.persistence.SourceStateEntity;
import com.babakbaylarli.jobradar.source.HtmlText;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

    public record DigestMessage(String html, Keyboard keyboard, List<JobEntity> included) {}

    // ---- single job -------------------------------------------------------------------------------------------

    public String jobCard(JobEntity job) {
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(escape(HtmlText.truncate(job.getTitle(), 200))).append("</b>\n");
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
        sb.append(number).append(". <b>").append(escape(HtmlText.truncate(job.getTitle(), 110))).append("</b>\n");
        sb.append("   ").append(companyLine(job)).append(" · score ").append(job.getScore()).append('\n');
        if (!job.getMatchedKeywords().isEmpty()) {
            sb.append("   🎯 ").append(escape(String.join(", ", job.getMatchedKeywords()))).append('\n');
        }
        sb.append("   🔗 ").append(link(job.getUrl(), "View on " + sourceName(job.getSource()))).append('\n');
        return sb.toString();
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

    public String stats(List<String> sourceIds, List<SourceStats> stats, Map<String, SourceStateEntity> states, Instant now) {
        Map<String, SourceStats> byId = stats.stream().collect(Collectors.toMap(SourceStats::getSource, s -> s));
        List<String> ids = new ArrayList<>(sourceIds);
        byId.keySet().stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        if (ids.isEmpty()) {
            return "📊 No sources are enabled.";
        }
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
                    .append(", matched ").append(matched).append('\n').append("   ").append(pollStatus(states.get(id), now));
        }
        sb.append("\n\nTotal: seen ").append(totalSeen).append(", matched ").append(totalMatched);
        return sb.toString();
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
                /saved — jobs you saved
                /stats — jobs seen / matched per source (7 days)
                /keywords — current matching config
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
        return switch (id) {
            case "weworkremotely" -> "We Work Remotely";
            case "remotive" -> "Remotive";
            case "remoteok" -> "RemoteOK";
            case "hackernews" -> "Hacker News";
            default -> id;
        };
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
