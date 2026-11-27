package com.babakbaylarli.jobradar.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.babakbaylarli.jobradar.matching.JobIngestService;
import com.babakbaylarli.jobradar.matching.JobIngestService.IngestResult;
import com.babakbaylarli.jobradar.persistence.SourceStateEntity;
import com.babakbaylarli.jobradar.persistence.SourceStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SourcePollerTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    private final JobIngestService ingest = mock(JobIngestService.class);
    private final SourceStateRepository states = mock(SourceStateRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private static JobSource source(String id, Duration interval, List<Job> jobs, RuntimeException failure) {
        JobSource source = mock(JobSource.class);
        when(source.id()).thenReturn(id);
        when(source.pollInterval()).thenReturn(interval);
        if (failure != null) {
            when(source.fetch()).thenThrow(failure);
        } else {
            when(source.fetch()).thenReturn(jobs);
        }
        return source;
    }

    private static Job aJob(String source) {
        return new Job(source, "1", "t", "c", "https://x.test/1", List.of(), "", null, null);
    }

    @Test
    void oneFailingSourceDoesNotBreakTheOthers() {
        JobSource broken = source("broken", Duration.ofHours(1), null, new SourceException("boom"));
        JobSource healthy = source("healthy", Duration.ofHours(1), List.of(aJob("healthy")), null);
        when(states.findById(any())).thenReturn(Optional.empty());
        when(ingest.ingest(any())).thenReturn(new IngestResult(1, 1, 0, 0, 0));
        SourcePoller poller = new SourcePoller(List.of(broken, healthy), ingest, states, clock);

        poller.pollDueSources();

        verify(ingest).ingest(List.of(aJob("healthy")));
        ArgumentCaptor<SourceStateEntity> saved = ArgumentCaptor.forClass(SourceStateEntity.class);
        verify(states, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).anySatisfy(s -> {
            assertThat(s.getSource()).isEqualTo("broken");
            assertThat(s.getLastStatus()).isEqualTo("FAILED");
            assertThat(s.getLastError()).isEqualTo("boom");
        });
        assertThat(saved.getAllValues()).anySatisfy(s -> {
            assertThat(s.getSource()).isEqualTo("healthy");
            assertThat(s.getLastStatus()).isEqualTo("OK");
            assertThat(s.getLastNew()).isEqualTo(1);
        });
    }

    @Test
    void anUnexpectedRuntimeErrorFromAdapterIsContained() {
        JobSource weird = source("weird", Duration.ofHours(1), null, new IllegalStateException("bug in adapter"));
        when(states.findById(any())).thenReturn(Optional.empty());
        SourcePoller poller = new SourcePoller(List.of(weird), ingest, states, clock);

        poller.pollDueSources(); // must not throw

        verify(ingest, never()).ingest(any());
    }

    @Test
    void sourceIsNotPolledBeforeItsIntervalElapses() {
        JobSource source = source("remotive", Duration.ofHours(6), List.of(), null);
        SourceStateEntity state = new SourceStateEntity("remotive");
        state.recordSuccess(NOW.minus(Duration.ofHours(5)), 10, 1);
        when(states.findById("remotive")).thenReturn(Optional.of(state));
        SourcePoller poller = new SourcePoller(List.of(source), ingest, states, clock);

        poller.pollDueSources();

        verify(source, never()).fetch();
        assertThat(poller.isDue(source, NOW.plus(Duration.ofHours(1)))).isTrue();
    }

    @Test
    void neverPolledSourceIsDueImmediately() {
        JobSource source = source("remotive", Duration.ofHours(6), List.of(), null);
        when(states.findById("remotive")).thenReturn(Optional.empty());

        assertThat(new SourcePoller(List.of(source), ingest, states, clock).isDue(source, NOW)).isTrue();
    }

    @Test
    void failedSourceIsRetriedSoonerThanAFullInterval() {
        JobSource source = source("remotive", Duration.ofHours(6), List.of(), null);
        SourceStateEntity state = new SourceStateEntity("remotive");
        state.recordFailure(NOW.minus(Duration.ofMinutes(100)), "down"); // retry window = 6h / 4 = 90 min
        when(states.findById("remotive")).thenReturn(Optional.of(state));
        SourcePoller poller = new SourcePoller(List.of(source), ingest, states, clock);

        assertThat(poller.isDue(source, NOW)).isTrue();
        state.recordFailure(NOW.minus(Duration.ofMinutes(10)), "down");
        assertThat(poller.isDue(source, NOW)).isFalse();
    }
}
