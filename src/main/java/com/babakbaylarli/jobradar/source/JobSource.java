package com.babakbaylarli.jobradar.source;

import java.time.Duration;
import java.util.List;

/**
 * One adapter per official job feed. Implementations must only talk to documented public
 * endpoints (RSS / JSON APIs) and must never scrape HTML pages.
 */
public interface JobSource {

    /** Stable machine id, e.g. {@code remotive}. Stored with every job. */
    String id();

    /** Human readable name for messages and stats, e.g. {@code Remotive}. */
    String displayName();

    /** How often this source may be polled. */
    Duration pollInterval();

    /** Fetch the current jobs. Throws {@link SourceException} if the source is unavailable. */
    List<Job> fetch();
}
