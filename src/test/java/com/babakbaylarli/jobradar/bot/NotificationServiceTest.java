package com.babakbaylarli.jobradar.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.babakbaylarli.jobradar.persistence.BotStateRepository;
import com.babakbaylarli.jobradar.persistence.Delivery;
import com.babakbaylarli.jobradar.persistence.JobEntity;
import com.babakbaylarli.jobradar.persistence.JobRepository;
import com.babakbaylarli.jobradar.persistence.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The "never spam" rules (pause, quiet hours, hourly limit, digest) against real Postgres and a recording gateway. */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NotificationService.class, BotStateService.class, MessageFormatter.class, NotificationServiceTest.Config.class})
@TestPropertySource(properties = {
        "jobradar.telegram.owner-chat-id=42",
        "jobradar.notifications.max-per-hour=3",
        "jobradar.notifications.digest-size=3",
        "jobradar.notifications.digest-min-interval=PT6H",
        "jobradar.notifications.quiet-hours.enabled=true",
        "jobradar.notifications.quiet-hours.start=00:00",
        "jobradar.notifications.quiet-hours.end=08:00",
        "jobradar.notifications.quiet-hours.zone=Asia/Baku"})
class NotificationServiceTest {

    /** 14:00 in Baku (UTC+4) - outside quiet hours. */
    static final Instant DAYTIME = Instant.parse("2026-11-27T10:00:00Z");
    /** 02:00 in Baku - inside quiet hours. */
    static final Instant NIGHT = Instant.parse("2026-11-27T22:00:00Z");
    /** 08:05 in Baku the next morning. */
    static final Instant MORNING = Instant.parse("2026-11-28T04:05:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static class MutableClock extends Clock {
        Instant now = DAYTIME;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        MutableClock clock() {
            return new MutableClock();
        }

        @Bean
        RecordingGateway gateway() {
            return new RecordingGateway();
        }
    }

    @Autowired NotificationService service;
    @Autowired BotStateService state;
    @Autowired JobRepository jobs;
    @Autowired BotStateRepository botStateRepository;
    @Autowired MutableClock clock;
    @Autowired RecordingGateway gateway;

    @BeforeEach
    void reset() {
        clock.now = DAYTIME;
        gateway.sent.clear();
        gateway.enabled = true;
        gateway.failSends = false;
    }

    private JobEntity add(String externalId, int score, Delivery delivery) {
        return jobs.save(new JobEntity("remotive", externalId, "https://x.test/" + externalId, "Job " + externalId, "Acme",
                List.of(), "desc", null, DAYTIME, DAYTIME, score, List.of("java"), null, true, delivery));
    }

    private List<JobEntity> byDelivery(Delivery delivery) {
        return jobs.findAll().stream().filter(j -> j.getDelivery() == delivery).toList();
    }

    @Test
    void sendsBestMatchesAsIndividualCardsWithButtons() {
        add("low", 4, Delivery.PENDING);
        add("high", 9, Delivery.PENDING);
        state.recordDigest(DAYTIME.minus(Duration.ofHours(1))); // keep the digest out of this test

        service.flush();

        assertThat(gateway.sent).hasSize(2);
        assertThat(gateway.sent.get(0).html()).contains("Job high");   // best first
        assertThat(gateway.sent.get(1).html()).contains("Job low");
        assertThat(gateway.sent.get(0).chatId()).isEqualTo(42L);
        assertThat(gateway.sent.get(0).keyboard().rows().get(0).get(0).text()).isEqualTo("✍️ Draft proposal");
        assertThat(byDelivery(Delivery.SENT_LIVE)).hasSize(2).allSatisfy(j -> assertThat(j.getNotifiedAt()).isEqualTo(DAYTIME));
    }

    @Test
    void doesNotSendTheSameJobTwice() {
        add("one", 6, Delivery.PENDING);

        service.flush();
        service.flush();

        assertThat(gateway.sent).hasSize(1);
    }

    @Test
    void pausedBotSendsNothingAndKeepsJobsWaiting() {
        add("one", 6, Delivery.PENDING);
        state.setPaused(true);

        service.flush();

        assertThat(gateway.sent).isEmpty();
        assertThat(byDelivery(Delivery.PENDING)).hasSize(1);

        state.setPaused(false);
        service.flush();
        assertThat(gateway.sent).hasSize(1);
    }

    @Test
    void quietHoursQueueJobsAndTheMorningSendsOneDigest() {
        add("a", 5, Delivery.PENDING);
        add("b", 8, Delivery.PENDING);
        add("c", 6, Delivery.PENDING);
        clock.now = NIGHT;

        service.flush();

        assertThat(gateway.sent).isEmpty();
        assertThat(byDelivery(Delivery.DEFERRED)).hasSize(3);

        clock.now = NIGHT.plus(Duration.ofHours(3)); // 05:00 Baku: still quiet
        service.flush();
        assertThat(gateway.sent).isEmpty();

        clock.now = MORNING; // 08:05 Baku
        service.flush();

        assertThat(gateway.sent).hasSize(1); // ONE digest, not three cards
        String digest = gateway.sent.get(0).html();
        assertThat(digest).contains("📬").contains("3 matches");
        assertThat(digest.indexOf("Job b")).isLessThan(digest.indexOf("Job c")).isLessThan(digest.indexOf("Job a"));
        assertThat(gateway.sent.get(0).keyboard().rows()).hasSize(3);
        assertThat(byDelivery(Delivery.SENT_DIGEST)).hasSize(3);
        assertThat(state.lastDigestAt()).contains(MORNING);
    }

    @Test
    void hourlyLimitSendsTheBestAndDefersTheRestIntoTheNextDigest() {
        for (int i = 1; i <= 5; i++) {
            add("j" + i, 10 - i, Delivery.PENDING); // scores 9,8,7,6,5
        }
        state.recordDigest(DAYTIME.minus(Duration.ofHours(1))); // a digest went out recently

        service.flush();

        assertThat(gateway.sent).hasSize(3);
        assertThat(gateway.texts()).allSatisfy(t -> assertThat(t).doesNotContain("Job j4").doesNotContain("Job j5"));
        assertThat(byDelivery(Delivery.SENT_LIVE)).hasSize(3);
        assertThat(byDelivery(Delivery.DEFERRED)).extracting(JobEntity::getExternalId).containsExactlyInAnyOrder("j4", "j5");

        // later in the same hour nothing more goes out
        clock.now = DAYTIME.plus(Duration.ofMinutes(30));
        add("late", 9, Delivery.PENDING);
        service.flush();
        assertThat(gateway.sent).hasSize(3);
        assertThat(byDelivery(Delivery.DEFERRED)).extracting(JobEntity::getExternalId).contains("late");
    }

    @Test
    void cardsAlreadySentInTheLastHourCountAgainstTheLimit() {
        for (int i = 1; i <= 2; i++) {
            JobEntity sent = add("old" + i, 5, Delivery.PENDING);
            sent.markSent(Delivery.SENT_LIVE, DAYTIME.minus(Duration.ofMinutes(10)));
            jobs.save(sent);
        }
        add("n1", 9, Delivery.PENDING);
        add("n2", 8, Delivery.PENDING);
        state.recordDigest(DAYTIME.minus(Duration.ofHours(1)));

        service.flush();

        assertThat(gateway.sent).hasSize(1);
        assertThat(gateway.sent.get(0).html()).contains("Job n1");
        assertThat(byDelivery(Delivery.DEFERRED)).extracting(JobEntity::getExternalId).containsExactly("n2");
    }

    @Test
    void limitResetsAfterAnHour() {
        for (int i = 1; i <= 3; i++) {
            JobEntity sent = add("old" + i, 5, Delivery.PENDING);
            sent.markSent(Delivery.SENT_LIVE, DAYTIME.minus(Duration.ofMinutes(61)));
            jobs.save(sent);
        }
        add("fresh", 7, Delivery.PENDING);

        service.flush();

        assertThat(gateway.sent).hasSize(1);
        assertThat(gateway.sent.get(0).html()).contains("Job fresh");
    }

    @Test
    void overflowIsDigestedOnceTheMinimumIntervalHasPassed() {
        add("d1", 6, Delivery.DEFERRED);
        add("d2", 7, Delivery.DEFERRED);
        state.recordDigest(DAYTIME.minus(Duration.ofHours(2)));

        service.flush();
        assertThat(gateway.sent).isEmpty(); // too soon

        clock.now = DAYTIME.plus(Duration.ofHours(5)); // 7h after the last digest
        service.flush();
        assertThat(gateway.sent).hasSize(1);
        assertThat(gateway.sent.get(0).html()).contains("2 matches");
        service.flush();
        assertThat(gateway.sent).hasSize(1); // and not repeated
    }

    @Test
    void firstEverDigestGoesOutImmediatelyWhenSomethingIsDeferred() {
        add("d1", 6, Delivery.DEFERRED);

        service.flush();

        assertThat(gateway.sent).hasSize(1);
    }

    @Test
    void telegramOutageKeepsJobsPendingForTheNextAttempt() {
        add("one", 6, Delivery.PENDING);
        gateway.failSends = true;

        service.flush();

        assertThat(gateway.sent).isEmpty();
        assertThat(byDelivery(Delivery.PENDING)).hasSize(1);

        gateway.failSends = false;
        service.flush();
        assertThat(gateway.sent).hasSize(1);
        assertThat(byDelivery(Delivery.SENT_LIVE)).hasSize(1);
    }

    @Test
    void hiddenAndSavedJobsAreNeverNotified() {
        JobEntity hidden = add("hidden", 9, Delivery.PENDING);
        hidden.setUserStatus(UserStatus.HIDDEN);
        jobs.save(hidden);

        service.flush();

        assertThat(gateway.sent).isEmpty();
    }

    @Test
    void digestCommandSendsTopUnseenMatchesEvenWhenPausedOrQuiet() {
        add("p1", 5, Delivery.PENDING);
        add("p2", 9, Delivery.DEFERRED);
        add("p3", 7, Delivery.PENDING);
        add("p4", 3, Delivery.DEFERRED);
        state.setPaused(true);
        clock.now = NIGHT;

        int sent = service.sendDigestNow();

        assertThat(sent).isEqualTo(3); // digest-size = 3
        assertThat(gateway.sent).hasSize(1);
        assertThat(gateway.sent.get(0).html()).contains("Top unseen matches").contains("…and 1 more waiting");
        assertThat(byDelivery(Delivery.SENT_DIGEST)).extracting(JobEntity::getExternalId).containsExactlyInAnyOrder("p2", "p3", "p1");
        assertThat(service.sendDigestNow()).isEqualTo(1); // the remaining one
        assertThat(service.sendDigestNow()).isZero();
    }

    @Test
    void nothingHappensWithoutAGatewayOrOwner() {
        add("one", 6, Delivery.PENDING);
        gateway.enabled = false;

        service.flush();

        assertThat(gateway.sent).isEmpty();
        assertThat(service.sendDigestNow()).isZero();
        assertThat(byDelivery(Delivery.PENDING)).hasSize(1);
    }
}
