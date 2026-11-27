package com.babakbaylarli.jobradar.source;

import java.time.Instant;
import java.util.List;

/**
 * A job posting normalized from any source. {@code description} is plain text (HTML already stripped),
 * {@code salary} and {@code postedAt} may be {@code null}.
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
        Instant postedAt) {

    public Job {
        tags = tags == null ? List.of() : List.copyOf(tags);
        description = description == null ? "" : description;
        company = company == null ? "" : company;
    }
}
