package com.babakbaylarli.jobradar.source;

import java.time.Instant;
import java.util.List;

/**
 * A job posting normalized from any source. {@code description} is plain text (HTML already stripped),
 * {@code salary} (display text), {@code postedAt} and {@code budget} may be {@code null}.
 */
public record Job(
        String source,
        String externalId,
        String title,
        String company,
        String url,
        List<String> tags,
        String description,
        String salary,
        Instant postedAt,
        Market market,
        JobType type,
        Budget budget) {

    public Job {
        tags = tags == null ? List.of() : List.copyOf(tags);
        description = description == null ? "" : description;
        company = company == null ? "" : company;
        market = market == null ? Market.INTERNATIONAL : market;
        type = type == null ? JobType.REMOTE_JOB : type;
        if (salary == null && budget != null) {
            salary = budget.display();
        }
    }

    /** International remote job without a numeric budget (the original job shape). */
    public Job(String source, String externalId, String title, String company, String url, List<String> tags,
            String description, String salary, Instant postedAt) {
        this(source, externalId, title, company, url, tags, description, salary, postedAt, Market.INTERNATIONAL, JobType.REMOTE_JOB, null);
    }
}
