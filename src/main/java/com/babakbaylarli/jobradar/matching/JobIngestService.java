package com.babakbaylarli.jobradar.matching;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.persistence.Delivery;
import com.babakbaylarli.jobradar.persistence.JobEntity;
import com.babakbaylarli.jobradar.persistence.JobRepository;
import com.babakbaylarli.jobradar.source.HtmlText;
import com.babakbaylarli.jobradar.source.Job;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Turns fetched jobs into stored, scored rows: normalize URL, de-duplicate (by source + external id and by URL,
 * both in the batch and in Postgres), score, and queue matches for notification.
 */
@Service
public class JobIngestService {

    private static final Logger log = LoggerFactory.getLogger(JobIngestService.class);
    private static final int MAX_URL_LENGTH = 2000;

    public record IngestResult(int fetched, int created, int matched, int duplicates, int rejected) {}

    private final JobRepository repository;
    private final KeywordScorer scorer;
    private final JobRadarProperties.Matching matching;
    private final Clock clock;

    public JobIngestService(JobRepository repository, KeywordScorer scorer, JobRadarProperties properties, Clock clock) {
        this.repository = repository;
        this.scorer = scorer;
        this.matching = properties.matching();
        this.clock = clock;
    }

    public IngestResult ingest(List<Job> jobs) {
        Instant now = clock.instant();
        Set<String> seenInBatch = new HashSet<>();
        int created = 0;
        int matched = 0;
        int duplicates = 0;
        int rejected = 0;

        for (Job job : jobs) {
            String url = UrlNormalizer.normalize(job.url());
            if (!isValid(job, url)) {
                rejected++;
                continue;
            }
            // non-short-circuit '&' on purpose: register both keys even when the first one is already known
            boolean freshInBatch = seenInBatch.add(job.source() + "\u0000" + job.externalId()) & seenInBatch.add(url);
            if (!freshInBatch
                    || repository.existsBySourceAndExternalId(job.source(), job.externalId())
                    || repository.existsByUrl(url)) {
                duplicates++;
                continue;
            }

            ScoreResult result = scorer.score(job);
            boolean fresh = job.postedAt() == null || job.postedAt().isAfter(now.minus(matching.notifyMaxAge()));
            Delivery delivery = result.matched() && fresh ? Delivery.PENDING : Delivery.NONE;
            try {
                repository.save(new JobEntity(
                        job.source(),
                        job.externalId(),
                        url,
                        HtmlText.truncate(job.title(), 500),
                        HtmlText.truncate(job.company(), 255),
                        job.tags(),
                        HtmlText.truncate(job.description(), 20_000),
                        job.salary() == null ? null : HtmlText.truncate(job.salary(), 255),
                        job.postedAt(),
                        now,
                        result.score(),
                        result.matchedKeywords(),
                        result.excludedBy(),
                        result.matched(),
                        delivery));
                created++;
                if (result.matched()) {
                    matched++;
                }
            } catch (DataIntegrityViolationException e) {
                // Lost a race with a concurrent poll: the unique constraints are the source of truth.
                duplicates++;
            }
        }
        log.info("event=ingest fetched={} created={} matched={} duplicates={} rejected={}",
                jobs.size(), created, matched, duplicates, rejected);
        return new IngestResult(jobs.size(), created, matched, duplicates, rejected);
    }

    private static boolean isValid(Job job, String url) {
        return job.source() != null && !job.source().isBlank()
                && job.externalId() != null && !job.externalId().isBlank()
                && job.title() != null && !job.title().isBlank()
                && !url.isBlank() && url.length() <= MAX_URL_LENGTH;
    }
}
