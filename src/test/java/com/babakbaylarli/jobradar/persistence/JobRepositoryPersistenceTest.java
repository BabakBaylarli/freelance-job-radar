package com.babakbaylarli.jobradar.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the real Flyway migration against a real PostgreSQL and exercises the repositories. */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class JobRepositoryPersistenceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JobRepository jobs;

    @Autowired
    BotStateRepository botState;

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    /** The constraint tests run without the test transaction (like production, where each save commits on its own). */
    @AfterEach
    void cleanUp() {
        jobs.deleteAll();
    }

    private JobEntity job(String source, String externalId, String url, int score, boolean matched, Delivery delivery) {
        return new JobEntity(source, externalId, url, "Title " + externalId, "Acme", List.of("java", "Remote, Worldwide"),
                "Some description", "$100k", NOW.minusSeconds(60), NOW, score,
                matched ? List.of("java", "spring boot") : List.of(), null, matched, delivery);
    }

    @Test
    void savesAndReadsBackArraysAndFields() {
        JobEntity saved = jobs.saveAndFlush(job("remotive", "a1", "https://x.test/a1", 6, true, Delivery.PENDING));

        JobEntity found = jobs.findById(saved.getId()).orElseThrow();
        assertThat(found.getTags()).containsExactly("java", "Remote, Worldwide");
        assertThat(found.getMatchedKeywords()).containsExactly("java", "spring boot");
        assertThat(found.getSalary()).isEqualTo("$100k");
        assertThat(found.getDelivery()).isEqualTo(Delivery.PENDING);
        assertThat(found.getUserStatus()).isEqualTo(UserStatus.NONE);
        assertThat(found.toJob().tags()).containsExactly("java", "Remote, Worldwide");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void enforcesUniquenessBySourceAndExternalId() {
        jobs.saveAndFlush(job("remoteok", "dup", "https://x.test/one", 1, false, Delivery.NONE));

        assertThatThrownBy(() -> jobs.saveAndFlush(job("remoteok", "dup", "https://x.test/two", 1, false, Delivery.NONE)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jobs.existsBySourceAndExternalId("remoteok", "dup")).isTrue();
        assertThat(jobs.existsBySourceAndExternalId("remotive", "dup")).isFalse(); // same id, other source is fine
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void enforcesUniquenessByUrlAcrossSources() {
        jobs.saveAndFlush(job("remoteok", "u1", "https://x.test/shared", 1, false, Delivery.NONE));

        assertThatThrownBy(() -> jobs.saveAndFlush(job("remotive", "u2", "https://x.test/shared", 1, false, Delivery.NONE)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jobs.existsByUrl("https://x.test/shared")).isTrue();
    }

    @Test
    void queueIsOrderedByScoreAndSkipsHiddenJobs() {
        jobs.save(job("remotive", "q1", "https://x.test/q1", 4, true, Delivery.PENDING));
        jobs.save(job("remotive", "q2", "https://x.test/q2", 9, true, Delivery.DEFERRED));
        JobEntity hidden = job("remotive", "q3", "https://x.test/q3", 10, true, Delivery.DEFERRED);
        hidden.setUserStatus(UserStatus.HIDDEN);
        jobs.save(hidden);
        jobs.save(job("remotive", "q4", "https://x.test/q4", 7, true, Delivery.SENT_LIVE));

        List<JobEntity> queue = jobs.findByDeliveryInAndUserStatusOrderByScoreDescIdDesc(
                List.of(Delivery.PENDING, Delivery.DEFERRED), UserStatus.NONE, PageRequest.of(0, 10));

        assertThat(queue).extracting(JobEntity::getExternalId).containsExactly("q2", "q1");
        assertThat(jobs.countByDeliveryAndUserStatus(Delivery.DEFERRED, UserStatus.NONE)).isEqualTo(1);
    }

    @Test
    void countsLiveNotificationsInTheLastHourForRateLimiting() {
        JobEntity recent = job("remotive", "r1", "https://x.test/r1", 5, true, Delivery.PENDING);
        recent.markSent(Delivery.SENT_LIVE, NOW.minusSeconds(600));
        JobEntity old = job("remotive", "r2", "https://x.test/r2", 5, true, Delivery.PENDING);
        old.markSent(Delivery.SENT_LIVE, NOW.minusSeconds(7200));
        JobEntity digest = job("remotive", "r3", "https://x.test/r3", 5, true, Delivery.PENDING);
        digest.markSent(Delivery.SENT_DIGEST, NOW.minusSeconds(60));
        jobs.saveAll(List.of(recent, old, digest));

        assertThat(jobs.countByDeliveryAndNotifiedAtAfter(Delivery.SENT_LIVE, NOW.minusSeconds(3600))).isEqualTo(1);
    }

    @Test
    void savedJobsAreListedNewestFirst() {
        JobEntity first = job("remotive", "s1", "https://x.test/s1", 5, true, Delivery.SENT_LIVE);
        first.setUserStatus(UserStatus.SAVED);
        JobEntity second = job("remotive", "s2", "https://x.test/s2", 5, true, Delivery.SENT_LIVE);
        second.setUserStatus(UserStatus.SAVED);
        jobs.saveAll(List.of(first, second));

        assertThat(jobs.findByUserStatusOrderByIdDesc(UserStatus.SAVED, PageRequest.of(0, 10)))
                .extracting(JobEntity::getExternalId).containsExactly("s2", "s1");
    }

    @Test
    void statsCountSeenAndMatchedPerSourceSinceADate() {
        jobs.save(job("remotive", "t1", "https://x.test/t1", 5, true, Delivery.PENDING));
        jobs.save(job("remotive", "t2", "https://x.test/t2", 0, false, Delivery.NONE));
        jobs.save(job("remoteok", "t3", "https://x.test/t3", 0, false, Delivery.NONE));
        JobEntity ancient = new JobEntity("remoteok", "t4", "https://x.test/t4", "Old", "", List.of(), "", null, null,
                NOW.minusSeconds(30L * 24 * 3600), 5, List.of("java"), null, true, Delivery.NONE);
        jobs.save(ancient);

        List<JobRepository.SourceStats> stats = jobs.statsSince(NOW.minusSeconds(7L * 24 * 3600));

        assertThat(stats).extracting(JobRepository.SourceStats::getSource).containsExactly("remoteok", "remotive");
        assertThat(stats.get(0).getSeen()).isEqualTo(1);   // the 30-day-old job is outside the window
        assertThat(stats.get(0).getMatched()).isZero();
        assertThat(stats.get(1).getSeen()).isEqualTo(2);
        assertThat(stats.get(1).getMatched()).isEqualTo(1);
    }

    @Test
    void migrationSeedsTheSingletonBotStateRow() {
        BotStateEntity state = botState.findById(BotStateEntity.SINGLETON_ID).orElseThrow();

        assertThat(state.isPaused()).isFalse();
        assertThat(state.getLastDigestAt()).isNull();
    }

    @Test
    void sourceStateRoundTrips(@Autowired SourceStateRepository states) {
        SourceStateEntity state = new SourceStateEntity("remotive");
        state.recordFailure(NOW, "HTTP 503");
        states.saveAndFlush(state);

        SourceStateEntity found = states.findById("remotive").orElseThrow();
        assertThat(found.getLastStatus()).isEqualTo("FAILED");
        assertThat(found.getLastError()).isEqualTo("HTTP 503");
        assertThat(found.getLastPolledAt()).isEqualTo(NOW);
    }
}
