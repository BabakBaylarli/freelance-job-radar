package com.babakbaylarli.jobradar.persistence;

/** Where a job is in the notification pipeline. */
public enum Delivery {
    /** Not a match - never sent. */
    NONE,
    /** A match waiting to be sent as an individual message. */
    PENDING,
    /** A match held back (quiet hours / rate limit) for the next digest. */
    DEFERRED,
    SENT_LIVE,
    SENT_DIGEST
}
