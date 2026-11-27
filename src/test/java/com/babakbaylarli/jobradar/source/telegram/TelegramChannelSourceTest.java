package com.babakbaylarli.jobradar.source.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.babakbaylarli.jobradar.TestSupport;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.config.JobRadarProperties.Channel;
import com.babakbaylarli.jobradar.config.JobRadarProperties.TelegramChannels;
import com.babakbaylarli.jobradar.source.Budget;
import com.babakbaylarli.jobradar.source.Job;
import com.babakbaylarli.jobradar.source.JobType;
import com.babakbaylarli.jobradar.source.Market;
import com.babakbaylarli.jobradar.source.SourceException;
import it.tdlight.jni.TdApi;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelegramChannelSourceTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    /** In-memory reader: posts per channel, or an error. */
    static class FakeReader implements ChannelReader {
        final Map<String, List<ChannelPost>> posts;
        final Map<String, String> errors;
        final List<String> asked = new ArrayList<>();
        boolean closed;

        FakeReader(Map<String, List<ChannelPost>> posts, Map<String, String> errors) {
            this.posts = posts;
            this.errors = errors;
        }

        @Override
        public List<ChannelPost> latestPosts(String username, int limit) throws ChannelReaderException {
            asked.add(username);
            if (errors.containsKey(username)) {
                throw new ChannelReaderException(errors.get(username));
            }
            return posts.getOrDefault(username, List.of());
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static JobRadarProperties props(boolean credentials, Channel... channels) {
        TelegramChannels config = new TelegramChannels(true, Duration.ofMinutes(30), credentials ? "12345" : null, credentials ? "hash" : null,
                credentials ? "+994500000000" : null, "/tmp/tdlight-test", List.of(channels), 50);
        JobRadarProperties base = TestSupport.props("http://unused");
        JobRadarProperties.Sources s = base.sources();
        return new JobRadarProperties(base.telegram(), base.ai(), base.matching(), base.notifications(), base.http(),
                new JobRadarProperties.Sources(s.weWorkRemotely(), s.remotive(), s.remoteOk(), s.hackerNews(), s.freelancer(), s.himalayas(), s.jobicy(),
                        s.workingNomads(), s.flRu(), s.hh(), s.freelancehunt(), s.dou(), s.emailAlerts(), config), base.profilePath(), base.apply());
    }

    private static ChannelPost post(String channel, long id, String text) {
        return new ChannelPost(channel, id, text, NOW);
    }

    private static TelegramChannelSource source(FakeReader reader, Channel... channels) {
        return new TelegramChannelSource(props(true, channels), reader, 0);
    }

    @Test
    void everyPostBecomesOneJobWithLinkChannelMarketAndBudget() {
        FakeReader reader = new FakeReader(Map.of("ru_freelance", List.of(
                post("ru_freelance", 1042, "Нужен Java-разработчик для доработки сервиса платежей\nБюджет: 25 000 ₽, срок 2 недели, Spring Boot + Kafka"))), Map.of());

        List<Job> jobs = source(reader, new Channel("ru_freelance", Market.RU, JobType.FREELANCE_PROJECT)).fetch();

        assertThat(jobs).hasSize(1);
        Job job = jobs.get(0);
        assertThat(job.source()).isEqualTo("telegram:ru_freelance");
        assertThat(job.externalId()).isEqualTo("1042");
        assertThat(job.url()).isEqualTo("https://t.me/ru_freelance/1042");
        assertThat(job.title()).isEqualTo("Нужен Java-разработчик для доработки сервиса платежей");
        assertThat(job.description()).contains("Spring Boot + Kafka");
        assertThat(job.tags()).containsExactly("ru_freelance");
        assertThat(job.market()).isEqualTo(Market.RU);
        assertThat(job.type()).isEqualTo(JobType.FREELANCE_PROJECT);
        assertThat(job.budget()).isEqualTo(Budget.of(25000, null, "RUB", false));
        assertThat(job.postedAt()).isEqualTo(NOW);
    }

    @Test
    void theChannelsMarketPicksTheDefaultCurrencyForUnknownCurrencyWords() {
        Job ua = TelegramChannelSource.toJob(new Channel("ua_jobs", Market.UA, JobType.VACANCY),
                post("ua_jobs", 1, "Шукаємо Java розробника, зарплата 3000-4000 грн, віддалено")).orElseThrow();
        Job az = TelegramChannelSource.toJob(new Channel("az_jobs", Market.AZ, JobType.VACANCY),
                post("az_jobs", 2, "Java proqramçı axtarılır, maaş 2500 AZN, uzaqdan iş imkanı")).orElseThrow();

        assertThat(ua.budget()).isEqualTo(Budget.of(3000, 4000, "UAH", false));
        assertThat(az.budget()).isEqualTo(Budget.of(2500, null, "AZN", false));
        assertThat(az.market()).isEqualTo(Market.AZ);
    }

    @Test
    void tooShortPostsAreIgnored() {
        assertThat(TelegramChannelSource.toJob(new Channel("c", Market.RU, null), post("c", 1, "+1"))).isEmpty();
        assertThat(TelegramChannelSource.toJob(new Channel("c", Market.RU, null), post("c", 2, "🔥🔥🔥 спасибо!"))).isEmpty();
    }

    @Test
    void oneFailingChannelDoesNotStopTheOthers() {
        FakeReader reader = new FakeReader(
                Map.of("good", List.of(post("good", 7, "Backend developer needed for a fintech project, remote, Java and Kafka"))),
                Map.of("flooded", "Telegram flood wait while reading @flooded: FLOOD_WAIT_30"));

        List<Job> jobs = source(reader, new Channel("flooded", Market.RU, null), new Channel("good", Market.RU, null)).fetch();

        assertThat(jobs).hasSize(1);
        assertThat(reader.asked).containsExactly("flooded", "good");
    }

    @Test
    void ifEveryChannelFailsTheSourceFailsWithTheLastReason() {
        FakeReader reader = new FakeReader(Map.of(), Map.of("a", "The Telegram session is not logged in", "b", "The Telegram session is not logged in"));

        assertThatThrownBy(() -> source(reader, new Channel("a", Market.RU, null), new Channel("b", Market.RU, null)).fetch())
                .isInstanceOf(SourceException.class).hasMessageContaining("not logged in");
    }

    @Test
    void isSkippedWithAReasonWithoutCredentialsOrChannels() {
        FakeReader reader = new FakeReader(Map.of(), Map.of());

        assertThat(new TelegramChannelSource(props(false, new Channel("x", Market.RU, null)), reader, 0).unavailableReason())
                .hasValueSatisfying(r -> assertThat(r).contains("TELEGRAM_API_ID"));
        assertThat(new TelegramChannelSource(props(true), reader, 0).unavailableReason())
                .hasValueSatisfying(r -> assertThat(r).contains("channels.yml"));
        assertThat(source(reader, new Channel("x", Market.RU, null)).unavailableReason()).isEmpty();
    }

    @Test
    void channelUsernamesAreNormalizedAndBlankOnesAreIgnored() {
        FakeReader reader = new FakeReader(Map.of(), Map.of());

        source(reader, new Channel("@with_at", Market.RU, null), new Channel("  ", Market.RU, null)).fetch();

        assertThat(reader.asked).containsExactly("with_at");
        assertThat(new Channel("x", null, null).type()).isEqualTo(JobType.FREELANCE_PROJECT);
    }

    @Test
    void shutdownClosesTheReader() {
        FakeReader reader = new FakeReader(Map.of(), Map.of());

        source(reader, new Channel("x", Market.RU, null)).shutdown();

        assertThat(reader.closed).isTrue();
    }

    // ---- pure helpers of the TDLight reader ---------------------------------------------------------------------

    @Test
    void publicMessageIdIsTheTdlibIdShiftedBy20Bits() {
        assertThat(TdLightChannelReader.publicMessageId(1042L << 20)).isEqualTo(1042L);
    }

    @Test
    void textIsTakenFromTextMessagesAndCaptionsOnly() {
        TdApi.FormattedText text = new TdApi.FormattedText("Java developer needed", new TdApi.TextEntity[0]);
        assertThat(TdLightChannelReader.textOf(new TdApi.MessageText(text, null, null))).isEqualTo("Java developer needed");
        assertThat(TdLightChannelReader.textOf(new TdApi.MessageText(null, null, null))).isNull();
        assertThat(TdLightChannelReader.textOf(new TdApi.MessageContact())).isNull();
    }

    @Test
    void loginCliExplainsMissingConfigurationWithoutTouchingTheNetwork() {
        assertThat(TelegramLoginCli.run(Map.of())).isEqualTo(2);
        assertThat(TelegramLoginCli.run(Map.of("TELEGRAM_API_ID", "1", "TELEGRAM_API_HASH", "h"))).isEqualTo(2);
    }
}
