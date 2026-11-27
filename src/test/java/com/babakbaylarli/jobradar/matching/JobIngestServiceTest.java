package com.babakbaylarli.jobradar.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.babakbaylarli.jobradar.TestSupport;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.config.JobRadarProperties.Keyword;
import com.babakbaylarli.jobradar.matching.JobIngestService.IngestResult;
import com.babakbaylarli.jobradar.persistence.Delivery;
import com.babakbaylarli.jobradar.persistence.JobEntity;
import com.babakbaylarli.jobradar.persistence.JobRepository;
import com.babakbaylarli.jobradar.source.Job;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class JobIngestServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    @Mock
    JobRepository repository;

    JobIngestService service;

    @BeforeEach
    void setUp() {
        JobRadarProperties base = TestSupport.props("http://unused");
        JobRadarProperties props = new JobRadarProperties(base.telegram(), base.ai(),
                TestSupport.matching(4, List.of(Keyword.of("java", 3), Keyword.of("kafka", 2)), List.of("onsite")),
                base.notifications(), base.http(), base.sources(), base.profilePath(), base.apply());
        service = new JobIngestService(repository, new KeywordScorer(props), props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Job job(String id, String url, String title, Instant postedAt) {
        return new Job("remotive", id, title, "Acme", url, List.of("kafka"), "Great role", null, postedAt);
    }

    @Test
    void storesScoredMatchAndQueuesItForNotification() {
        IngestResult result = service.ingest(List.of(job("1", "https://x.test/jobs/1/?utm_source=feed", "Java Engineer", NOW.minusSeconds(3600))));

        ArgumentCaptor<JobEntity> saved = ArgumentCaptor.forClass(JobEntity.class);
        verify(repository).save(saved.capture());
        JobEntity entity = saved.getValue();
        assertThat(entity.getUrl()).isEqualTo("https://x.test/jobs/1"); // tracking params removed before storing
        assertThat(entity.getScore()).isEqualTo(5);
        assertThat(entity.getMatchedKeywords()).containsExactlyInAnyOrder("java", "kafka");
        assertThat(entity.isMatched()).isTrue();
        assertThat(entity.getDelivery()).isEqualTo(Delivery.PENDING);
        assertThat(entity.getFirstSeenAt()).isEqualTo(NOW);
        assertThat(result).isEqualTo(new IngestResult(1, 1, 1, 0, 0));
    }

    @Test
    void storesNonMatchesWithoutNotifying() {
        service.ingest(List.of(job("2", "https://x.test/jobs/2", "Gardener", NOW)));

        ArgumentCaptor<JobEntity> saved = ArgumentCaptor.forClass(JobEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isMatched()).isFalse();
        assertThat(saved.getValue().getDelivery()).isEqualTo(Delivery.NONE);
    }

    @Test
    void skipsJobAlreadyStoredBySourceAndExternalId() {
        when(repository.existsBySourceAndExternalId("remotive", "1")).thenReturn(true);

        IngestResult result = service.ingest(List.of(job("1", "https://x.test/jobs/1", "Java Engineer", NOW)));

        verify(repository, never()).save(any());
        assertThat(result.duplicates()).isEqualTo(1);
    }

    @Test
    void skipsJobWithAlreadyKnownUrlEvenFromAnotherSource() {
        when(repository.existsByUrl("https://x.test/jobs/1")).thenReturn(true);
        Job crossPosted = new Job("remoteok", "99", "Java Engineer", "Acme", "https://x.test/jobs/1/", List.of(), "", null, NOW);

        IngestResult result = service.ingest(List.of(crossPosted));

        verify(repository, never()).save(any());
        assertThat(result.duplicates()).isEqualTo(1);
    }

    @Test
    void deduplicatesInsideOneBatchByIdAndByUrl() {
        IngestResult result = service.ingest(List.of(
                job("1", "https://x.test/jobs/1", "Java Engineer", NOW),
                job("1", "https://x.test/jobs/other", "Java Engineer (repost)", NOW), // same external id
                job("3", "https://x.test/jobs/1?utm_medium=mail", "Java Engineer again", NOW))); // same url

        verify(repository, times(1)).save(any());
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.duplicates()).isEqualTo(2);
    }

    @Test
    void staleMatchesAreStoredButNeverSent() {
        service.ingest(List.of(job("4", "https://x.test/jobs/4", "Java Engineer", NOW.minusSeconds(30L * 24 * 3600))));

        ArgumentCaptor<JobEntity> saved = ArgumentCaptor.forClass(JobEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isMatched()).isTrue();
        assertThat(saved.getValue().getDelivery()).isEqualTo(Delivery.NONE);
    }

    @Test
    void unknownPostedDateCountsAsFresh() {
        service.ingest(List.of(job("5", "https://x.test/jobs/5", "Java Engineer", null)));

        ArgumentCaptor<JobEntity> saved = ArgumentCaptor.forClass(JobEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getDelivery()).isEqualTo(Delivery.PENDING);
    }

    @Test
    void rejectsInvalidJobs() {
        IngestResult result = service.ingest(List.of(
                new Job("remotive", "", "No id", "A", "https://x.test/a", List.of(), "", null, null),
                new Job("remotive", "7", "", "A", "https://x.test/b", List.of(), "", null, null),
                new Job("remotive", "8", "No url", "A", " ", List.of(), "", null, null)));

        verify(repository, never()).save(any());
        assertThat(result.rejected()).isEqualTo(3);
    }

    @Test
    void uniqueConstraintRaceIsTreatedAsDuplicate() {
        when(repository.save(any(JobEntity.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));
        // not there at the pre-check, but present when we look again after the failed insert (a concurrent poll won)
        when(repository.existsBySourceAndExternalId("remotive", "6")).thenReturn(false, true);

        IngestResult result = service.ingest(List.of(job("6", "https://x.test/jobs/6", "Java Engineer", NOW)));

        assertThat(result.created()).isZero();
        assertThat(result.duplicates()).isEqualTo(1);
    }

    @Test
    void otherDatabaseErrorsAreRejectedNotSilentlyCalledDuplicates() {
        when(repository.save(any(JobEntity.class))).thenThrow(new DataIntegrityViolationException("value too long"));

        IngestResult result = service.ingest(List.of(job("7", "https://x.test/jobs/7", "Java Engineer", NOW)));

        assertThat(result.duplicates()).isZero();
        assertThat(result.rejected()).isEqualTo(1);
    }

    @Test
    void sameJobOnAnotherPlatformIsMergedIntoTheFirstNotificationInsteadOfSavedAgain() {
        JobEntity original = new JobEntity("remotive", "r1", "https://remotive.test/1", "Senior Java Engineer", "Acme", List.of(), "", null,
                NOW, NOW.minusSeconds(3600), 8, List.of("java"), null, true, com.babakbaylarli.jobradar.persistence.Delivery.PENDING);
        when(repository.findFirstByDedupKeyAndFirstSeenAtAfterOrderByIdAsc(any(), any())).thenReturn(java.util.Optional.of(original));
        Job crossPost = new Job("remoteok", "o9", "Senior Java Engineer", "ACME", "https://remoteok.test/9", List.of(), "", null, NOW);

        IngestResult result = service.ingest(List.of(crossPost));

        assertThat(result.created()).isZero();
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(original.getAlsoOn()).containsExactly("remoteok|https://remoteok.test/9");
        verify(repository).save(original); // only the original is updated, no second row
    }

    @Test
    void relistingOnTheSamePlatformIsNotAMerge() {
        JobEntity original = new JobEntity("remotive", "r1", "https://remotive.test/1", "Senior Java Engineer", "Acme", List.of(), "", null,
                NOW, NOW.minusSeconds(3600), 8, List.of("java"), null, true, com.babakbaylarli.jobradar.persistence.Delivery.PENDING);
        when(repository.findFirstByDedupKeyAndFirstSeenAtAfterOrderByIdAsc(any(), any())).thenReturn(java.util.Optional.of(original));
        Job relist = new Job("remotive", "r2", "Senior Java Engineer", "Acme", "https://remotive.test/2", List.of(), "", null, NOW);

        IngestResult result = service.ingest(List.of(relist));

        assertThat(result.created()).isEqualTo(1);
        assertThat(original.getAlsoOn()).isEmpty();
    }

    @Test
    void marketTypeBudgetAndDedupKeyAreStoredWithTheJob() {
        Job project = new Job("flru", "5", "Java payment service", "", "https://fl.test/p/5", List.of("java"), "kafka", null, NOW,
                com.babakbaylarli.jobradar.source.Market.RU, com.babakbaylarli.jobradar.source.JobType.FREELANCE_PROJECT,
                com.babakbaylarli.jobradar.source.Budget.of(45000, null, "RUB", false));

        service.ingest(List.of(project));

        ArgumentCaptor<JobEntity> saved = ArgumentCaptor.forClass(JobEntity.class);
        verify(repository).save(saved.capture());
        JobEntity entity = saved.getValue();
        assertThat(entity.getMarket()).isEqualTo(com.babakbaylarli.jobradar.source.Market.RU);
        assertThat(entity.getJobType()).isEqualTo(com.babakbaylarli.jobradar.source.JobType.FREELANCE_PROJECT);
        assertThat(entity.getCurrency()).isEqualTo("RUB");
        assertThat(entity.getDedupKey()).isEqualTo("java payment service|45000RUB");
        assertThat(entity.toJob().budget().ceiling()).isEqualByComparingTo("45000");
    }

    @Test
    void overlongFieldsAreClampedInsteadOfFailingTheInsert() {
        Job huge = new Job("remotive", "9", "Java ".repeat(300), "C".repeat(400), "https://x.test/jobs/9", List.of("kafka"), "d".repeat(30_000),
                "s".repeat(400), NOW);

        service.ingest(List.of(huge));

        ArgumentCaptor<JobEntity> saved = ArgumentCaptor.forClass(JobEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTitle().length()).isLessThanOrEqualTo(500);
        assertThat(saved.getValue().getCompany().length()).isLessThanOrEqualTo(255);
        assertThat(saved.getValue().getDescription().length()).isLessThanOrEqualTo(20_000);
        assertThat(saved.getValue().getSalary().length()).isLessThanOrEqualTo(255);
    }
}
