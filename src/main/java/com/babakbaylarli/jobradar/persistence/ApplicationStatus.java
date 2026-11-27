package com.babakbaylarli.jobradar.persistence;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** State of one application. Only the transitions in {@link #ALLOWED} are legal; everything else is a bug. */
public enum ApplicationStatus {
    /** A proposal exists; waiting for the owner's decision. */
    DRAFT,
    /** The owner pressed Edit; the next reply to the draft is the new text or an instruction. */
    EDITING,
    /** Waiting for a "yes" to a one-line confirmation (e.g. "this uses 1 of your bids"). */
    CONFIRMING,
    SENT,
    FAILED,
    /** Nothing was sent by the bot: the owner copied the text and opened the job link. */
    COPIED,
    CANCELLED;

    private static final Map<ApplicationStatus, Set<ApplicationStatus>> ALLOWED = Map.of(
            DRAFT, EnumSet.of(EDITING, CONFIRMING, SENT, FAILED, COPIED, CANCELLED, DRAFT),
            EDITING, EnumSet.of(DRAFT, CANCELLED),
            CONFIRMING, EnumSet.of(SENT, FAILED, DRAFT, CANCELLED),
            FAILED, EnumSet.of(DRAFT, CONFIRMING, SENT, FAILED, COPIED, CANCELLED),
            SENT, EnumSet.noneOf(ApplicationStatus.class),
            COPIED, EnumSet.noneOf(ApplicationStatus.class),
            CANCELLED, EnumSet.noneOf(ApplicationStatus.class));

    public boolean canMoveTo(ApplicationStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** Still being worked on (the owner can press buttons on it). */
    public boolean isOpen() {
        return this == DRAFT || this == EDITING || this == CONFIRMING || this == FAILED;
    }
}
