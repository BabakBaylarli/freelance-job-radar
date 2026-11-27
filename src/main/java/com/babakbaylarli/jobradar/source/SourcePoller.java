package com.babakbaylarli.jobradar.source;

import com.babakbaylarli.jobradar.matching.JobIngestService;
import com.babakbaylarli.jobradar.matching.JobIngestService.IngestResult;
import com.babakbaylarli.jobradar.persistence.SourceStateEntity;
import com.babakbaylarli.jobradar.persistence.SourceStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls every enabled {@link JobSource} on its own interval. A single {@code @Scheduled} tick looks at which
 * sources are due (last poll times are persisted, so restarts never re-poll early), and fetches the due ones in
 * parallel on virtual threads. A failing source is logged and recorded; it never affects the others.
 */
@Component
public class SourcePoller {

    private static final Logger log = LoggerFactory.getLogger(SourcePoller.class);
    private static final Duration MIN_FAILURE_RETRY = Duration.ofMinutes(15);

    private final List<JobSource> sources;
    private final JobIngestService ingestService;
    private final SourceStateRepository states;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SourcePoller(List<JobSource> sources, JobIngestService ingestService, SourceStateRepository states, Clock clock) {
        this.sources = sources;
        this.ingestService = ingestService;
        this.states = states;
        this.clock = clock;
        log.info("event=sources_enabled sources={}", sources.stream().map(JobSource::id).toList());
    }

    @Scheduled(initialDelayString = "${jobradar.poll-tick-initial-delay:PT10S}", fixedDelayString = "${jobradar.poll-tick:PT1M}")
    public void pollDueSources() {
        Instant now = clock.instant();
        List<? extends Future<?>> running = sources.stream()
                .filter(source -> isDue(source, now))
                .map(source -> executor.submit(() -> pollNow(source)))
                .toList();
        for (Future<?> future : running) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Unexpected error while polling", e); // pollNow already handles source errors
            }
        }
    }

    boolean isDue(JobSource source, Instant now) {
        SourceStateEntity state = states.findById(source.id()).orElse(null);
        if (state == null || state.getLastPolledAt() == null) {
            return true;
        }
        Duration wait = source.pollInterval();
        if ("FAILED".equals(state.getLastStatus())) {
            // Retry a failed source sooner than a full interval, but never more often than every 15 minutes.
            Duration retry = source.pollInterval().dividedBy(4);
            if (retry.compareTo(MIN_FAILURE_RETRY) < 0) {
                retry = MIN_FAILURE_RETRY;
            }
            wait = retry.compareTo(wait) < 0 ? retry : wait;
        }
        return !now.isBefore(state.getLastPolledAt().plus(wait));
    }

    /** Fetch, ingest and record the outcome of one source. Never throws. */
    public void pollNow(JobSource source) {
        MDC.put("source", source.id());
        SourceStateEntity state = states.findById(source.id()).orElseGet(() -> new SourceStateEntity(source.id()));
        try {
            List<Job> jobs = source.fetch();
            IngestResult result = ingestService.ingest(jobs);
            state.recordSuccess(clock.instant(), result.fetched(), result.created());
            log.info("event=poll_ok source={} fetched={} new={} matched={}",
                    source.id(), result.fetched(), result.created(), result.matched());
        } catch (Exception e) {
            state.recordFailure(clock.instant(), e.getMessage());
            log.warn("event=poll_failed source={} error={}", source.id(), e.getMessage());
        } finally {
            states.save(state);
            MDC.remove("source");
        }
    }
}
