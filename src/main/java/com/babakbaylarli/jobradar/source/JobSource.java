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

    /** Why this (enabled) source cannot run right now, e.g. a missing token. Such sources are skipped and shown in /sources. */
    default java.util.Optional<String> unavailableReason() {
        return java.util.Optional.empty();
    }

    /** Called by the poller after the fetched jobs were stored - e.g. to mark e-mails as read only now. */
    default void afterIngest() {}

    /** Fetch the current jobs. Throws {@link SourceException} if the source is unavailable. */
    List<Job> fetch();
}
