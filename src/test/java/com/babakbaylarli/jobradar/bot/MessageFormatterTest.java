package com.babakbaylarli.jobradar.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.babakbaylarli.jobradar.TestSupport;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.config.JobRadarProperties.Keyword;
import com.babakbaylarli.jobradar.persistence.Delivery;
import com.babakbaylarli.jobradar.persistence.JobEntity;
import com.babakbaylarli.jobradar.persistence.JobRepository.SourceStats;
import com.babakbaylarli.jobradar.persistence.SourceStateEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MessageFormatterTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");
    private final MessageFormatter formatter = new MessageFormatter();

    static JobEntity job(long id, String title, String company, String source, int score, List<String> keywords, String salary, String description) {
        JobEntity job = new JobEntity(source, "ext" + id, "https://remotive.com/jobs/" + id + "?a=1&b=2", title, company, List.of(),
                description, salary, NOW, NOW, score, keywords, null, true, Delivery.PENDING);
        ReflectionTestUtils.setField(job, "id", id);
        return job;
    }

    @Test
    void jobCardShowsEverythingTheOwnerNeedsAndLinksBackToTheSource() {
        JobEntity job = job(5, "Senior Java Engineer", "Pay<Lane>", "remoteok", 8, List.of("java", "kafka"), "$100,000 - $140,000",
                "We build payments.\n\nYou will own   Java services.");

        String card = formatter.jobCard(job);

        assertThat(card)
                .contains("<b>Senior Java Engineer</b>")
                .contains("🏢 Pay&lt;Lane&gt; · via RemoteOK")
                .contains("🎯 Score 8 — java, kafka")
                .contains("💰 $100,000 - $140,000")
                .contains("We build payments. You will own Java services.")
                .contains("<a href=\"https://remotive.com/jobs/5?a=1&amp;b=2\">View on RemoteOK</a>");
    }

    @Test
    void jobCardOmitsEmptyParts() {
        String card = formatter.jobCard(job(6, "Engineer", "", "hackernews", 4, List.of(), null, ""));

        assertThat(card).doesNotContain("🏢").doesNotContain("💰").contains("via Hacker News").contains("🎯 Score 4\n");
    }

    @Test
    void shortDescriptionIsTruncated() {
        String card = formatter.jobCard(job(7, "T", "C", "remotive", 4, List.of(), null, "word ".repeat(200)));

        assertThat(card).contains("…").hasSizeLessThan(1000);
    }

    @Test
    void jobKeyboardHasDraftSaveHideAndReflectsSavedState() {
        JobEntity job = job(9, "T", "C", "remotive", 4, List.of(), null, "");

        Keyboard fresh = formatter.jobKeyboard(job, false);
        Keyboard saved = formatter.jobKeyboard(job, true);

        assertThat(fresh.rows().get(0)).containsExactly(new Button("✍️ Draft proposal", "d:9"));
        assertThat(fresh.rows().get(1)).containsExactly(new Button("⭐ Save", "s:9"), new Button("🙈 Hide", "h:9"));
        assertThat(saved.rows().get(1).get(0).text()).isEqualTo("✅ Saved");
    }

    @Test
    void callbackDataFitsTelegramsLimit() {
        assertThat(("d:" + Long.MAX_VALUE).getBytes().length).isLessThanOrEqualTo(64);
    }

    @Test
    void proposalIsCopyFriendlyPreBlockAndEscaped() {
        String message = formatter.proposal(job(1, "Java <Dev>", "Acme", "remotive", 5, List.of(), null, ""), "Hi <team> & co,\nsee you?");

        assertThat(message).contains("<pre>Hi &lt;team&gt; &amp; co,\nsee you?</pre>").contains("Java &lt;Dev&gt;").contains("Open the job to apply");
        assertThat(message.length()).isLessThan(MessageFormatter.TELEGRAM_LIMIT);
    }

    @Test
    void hugeProposalStaysUnderTelegramLimit() {
        String message = formatter.proposal(job(1, "T", "C", "remotive", 5, List.of(), null, ""), "x ".repeat(5000));

        assertThat(message.length()).isLessThan(MessageFormatter.TELEGRAM_LIMIT);
    }

    @Test
    void escapingNeverPushesAProposalOverTheLimit() {
        String message = formatter.proposal(job(1, "T", "C", "remotive", 5, List.of(), null, ""), "<&>".repeat(3000)); // escapes to 4x longer

        assertThat(message.length()).isLessThan(MessageFormatter.TELEGRAM_LIMIT);
        assertThat(message).endsWith("Open the job to apply</a>").contains("</pre>");
    }

    @Test
    void digestNumbersEntriesAndOffersPerJobButtons() {
        List<JobEntity> jobs = List.of(job(1, "First", "A", "remotive", 9, List.of("java"), null, ""), job(2, "Second", "B", "remoteok", 7, List.of(), null, ""));

        MessageFormatter.DigestMessage digest = formatter.digest(jobs, 5, "Digest — 2 matches");

        assertThat(digest.html()).contains("📬 <b>Digest — 2 matches</b>").contains("1. 🌍 <b>First</b>").contains("2. 🌍 <b>Second</b>")
                .contains("…and 5 more waiting — send /digest");
        assertThat(digest.included()).hasSize(2);
        assertThat(digest.keyboard().rows()).hasSize(2);
        assertThat(digest.keyboard().rows().get(1)).containsExactly(new Button("✍️ 2", "d:2"), new Button("⭐ 2", "s:2"), new Button("🙈 2", "h:2"));
    }

    @Test
    void digestNeverExceedsTelegramLimitAndReportsWhatItIncluded() {
        List<JobEntity> many = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            many.add(job(i, "A rather long job title number " + i + " ".repeat(1) + "x".repeat(80), "Company " + i, "remotive", 5,
                    List.of("java", "spring", "kafka", "elasticsearch"), null, ""));
        }

        MessageFormatter.DigestMessage digest = formatter.digest(many, 0, "Digest");

        assertThat(digest.html().length()).isLessThan(MessageFormatter.TELEGRAM_LIMIT);
        assertThat(digest.included().size()).isLessThan(40).isGreaterThan(5);
        assertThat(digest.html()).contains("more waiting");
        assertThat(digest.keyboard().rows()).hasSameSizeAs(digest.included());
    }

    @Test
    void savedListHandlesEmptyAndNonEmpty() {
        assertThat(formatter.saved(List.of())).contains("No saved jobs yet");
        assertThat(formatter.saved(List.of(job(3, "Kept", "Acme", "remotive", 6, List.of(), null, "")))).contains("<b>Kept</b> — Acme").contains("score 6");
    }

    @Test
    void statsListEveryEnabledSourceEvenWithoutData() {
        SourceStats stats = new SourceStats() {
            public String getSource() { return "remotive"; }
            public long getSeen() { return 42; }
            public long getMatched() { return 5; }
        };
        SourceStateEntity ok = new SourceStateEntity("remotive");
        ok.recordSuccess(NOW.minus(Duration.ofHours(2)), 42, 3);
        SourceStateEntity bad = new SourceStateEntity("remoteok");
        bad.recordFailure(NOW.minus(Duration.ofMinutes(5)), "HTTP 429 <rate>");

        String text = formatter.stats(List.of("remotive", "remoteok", "hackernews"), List.of(stats), List.of(),
                Map.of("remotive", ok, "remoteok", bad), NOW);

        assertThat(text).contains("<b>Remotive</b> — seen 42, matched 5").contains("✅ polled 2h ago")
                .contains("<b>RemoteOK</b> — seen 0, matched 0").contains("⚠️ last poll failed 5m ago: HTTP 429 &lt;rate&gt;")
                .contains("<b>Hacker News</b> — seen 0").contains("not polled yet").contains("Total: seen 42, matched 5");
    }

    @Test
    void keywordsShowWeightsSortedExcludesAndSettings() {
        JobRadarProperties base = TestSupport.props("http://unused");
        JobRadarProperties props = new JobRadarProperties(base.telegram(), base.ai(),
                TestSupport.matching(4, List.of(Keyword.of("kafka", 2), Keyword.of("java", 3), Keyword.of("c++", 1)), List.of("onsite", "us only")),
                new JobRadarProperties.Notifications(6, 10, Duration.ofHours(6),
                        new com.babakbaylarli.jobradar.config.QuietHoursSettings(true, java.time.LocalTime.MIDNIGHT, java.time.LocalTime.of(8, 0), java.time.ZoneId.of("Asia/Baku"))),
                base.http(), base.sources(), base.profilePath());

        String text = formatter.keywords(props, List.of("Remotive", "RemoteOK"));

        assertThat(text).contains("Minimum score: <b>4</b>").contains("java 3 · kafka 2 · c++ 1").contains("onsite · us only")
                .contains("max 6/hour, quiet hours: 00:00-08:00 Asia/Baku").contains("Remotive, RemoteOK");
    }

    @Test
    void escapeOnlyTouchesWhatTelegramRequires() {
        assertThat(MessageFormatter.escape("a < b & c > d \"quoted\" é")).isEqualTo("a &lt; b &amp; c &gt; d \"quoted\" é");
        assertThat(MessageFormatter.humanize(Duration.ofSeconds(10))).isEqualTo("just now");
        assertThat(MessageFormatter.humanize(Duration.ofDays(3))).isEqualTo("3d");
    }

    @Test
    void helpAndWelcomeMentionAllCommands() {
        assertThat(formatter.help()).contains("/digest", "/saved", "/stats", "/keywords", "/pause", "/resume", "/help");
        assertThat(formatter.welcome(true, false)).contains("paused").contains("AI proposals are disabled");
        assertThat(formatter.welcome(false, true)).doesNotContain("paused").doesNotContain("disabled");
    }

    @Test
    void cardShowsMarketFlagTypeAndTheOtherPlatformsTheJobWasSeenOn() {
        JobEntity ru = new JobEntity(new com.babakbaylarli.jobradar.source.Job("flru", "1", "Сервис оплаты", "", "https://fl.example/p/1", List.of(), "d",
                null, NOW, com.babakbaylarli.jobradar.source.Market.RU, com.babakbaylarli.jobradar.source.JobType.FREELANCE_PROJECT,
                com.babakbaylarli.jobradar.source.Budget.of(45000, null, "RUB", false)), "https://fl.example/p/1", NOW, 8, List.of("java"), null, true,
                Delivery.PENDING, "key");
        ru.addAlsoOn("freelancehunt", "https://fh.example/p/9");
        ReflectionTestUtils.setField(ru, "id", 3L);

        String card = formatter.jobCard(ru);

        assertThat(card).contains("🇷🇺 Freelance project").contains("via FL.ru").contains("💰 45,000 RUB")
                .contains("<a href=\"https://fh.example/p/9\">Also on Freelancehunt</a>");
        assertThat(formatter.digest(List.of(ru), 0, "Digest").html()).contains("1. 🇷🇺 <b>Сервис оплаты</b>").contains(">Freelancehunt</a>");
    }

    @Test
    void emailAndTelegramSourcesGetReadableNames() {
        assertThat(MessageFormatter.sourceName("email:upwork")).isEqualTo("Upwork (email alert)");
        assertThat(MessageFormatter.sourceName("telegram:remote_jobs_ru")).isEqualTo("Telegram @remote_jobs_ru");
        assertThat(MessageFormatter.sourceName("flru")).isEqualTo("FL.ru");
        assertThat(MessageFormatter.sourceName("unknown-source")).isEqualTo("unknown-source");
    }

    @Test
    void statsIncludeTheMarketBreakdownAndSubSourcesShareTheirParentsPollState() {
        SourceStateEntity emailState = new SourceStateEntity("email");
        emailState.recordSuccess(NOW.minus(Duration.ofMinutes(10)), 3, 3);
        com.babakbaylarli.jobradar.persistence.JobRepository.SourceStats emailStats = new com.babakbaylarli.jobradar.persistence.JobRepository.SourceStats() {
            public String getSource() { return "email:upwork"; }
            public long getSeen() { return 4; }
            public long getMatched() { return 2; }
        };
        com.babakbaylarli.jobradar.persistence.JobRepository.MarketStats ru = new com.babakbaylarli.jobradar.persistence.JobRepository.MarketStats() {
            public com.babakbaylarli.jobradar.source.Market getMarket() { return com.babakbaylarli.jobradar.source.Market.RU; }
            public long getSeen() { return 30; }
            public long getMatched() { return 4; }
        };

        String text = formatter.stats(List.of(), List.of(emailStats), List.of(ru), Map.of("email", emailState), NOW);

        assertThat(text).contains("<b>Upwork (email alert)</b> — seen 4, matched 2").contains("polled 10m ago")
                .contains("<b>By market</b>").contains("🇷🇺 seen 30, matched 4");
    }

    @Test
    void sourcesViewDistinguishesActiveDisabledAndSkipped() {
        var entries = List.of(
                new com.babakbaylarli.jobradar.source.SourceCatalog.Entry("remotive", "Remotive", com.babakbaylarli.jobradar.source.SourceCatalog.Status.ACTIVE, null),
                new com.babakbaylarli.jobradar.source.SourceCatalog.Entry("freelancehunt", "Freelancehunt", com.babakbaylarli.jobradar.source.SourceCatalog.Status.SKIPPED, "FREELANCEHUNT_TOKEN is not set"),
                new com.babakbaylarli.jobradar.source.SourceCatalog.Entry("dou", "DOU", com.babakbaylarli.jobradar.source.SourceCatalog.Status.DISABLED, null));
        SourceStateEntity failed = new SourceStateEntity("remotive");
        failed.recordSuccess(NOW.minus(Duration.ofHours(5)), 5, 1);
        failed.recordFailure(NOW.minus(Duration.ofMinutes(20)), "HTTP 429 <slow down>");

        String text = formatter.sources(entries, Map.of("remotive", failed), Map.of("remotive", 12L), NOW);

        assertThat(text).contains("<b>Remotive</b> — 🟢 on · last success 5h ago").contains("⚠️ last error: HTTP 429 &lt;slow down&gt;")
                .contains("jobs in last 24h: 12").contains("<b>Freelancehunt</b> — 🟡 skipped: FREELANCEHUNT_TOKEN is not set")
                .contains("<b>DOU</b> — ⚪ off");
    }
}
