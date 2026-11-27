package com.babakbaylarli.jobradar.apply;

import com.babakbaylarli.jobradar.source.Job;
import java.util.Optional;

/**
 * Submits an approved proposal through a channel the platform explicitly allows (its official API, or e-mail / Telegram to
 * a contact the posting itself names). Platforms without such a channel have no sender and use "Copy &amp; open".
 * <p>
 * A sender is only ever called after the owner approved <em>that specific application</em>; it must never retry a
 * submission on its own.
 */
public interface ProposalSender {

    /** Short stable id stored with the application: {@code freelancer}, {@code email}, {@code telegram}. */
    String platform();

    /** Human readable route, e.g. "Freelancer.com API". */
    String route();

    /** Can this sender deliver an application for the job (right platform, a contact exists)? */
    boolean supports(Job job);

    /** Why it cannot run right now (missing token or credentials). Empty when ready. */
    Optional<String> unavailableReason();

    /** Who receives the application, shown to the owner before sending ({@code a@b.c} or {@code @username}). */
    default Optional<String> recipient(Job job) {
        return Optional.empty();
    }

    /** One-line warning shown before sending when the action is paid or limited (e.g. uses one of the monthly bids). */
    default Optional<String> confirmation(Job job) {
        return Optional.empty();
    }

    /** Whether the application needs a bid amount and delivery days. */
    default boolean needsBid() {
        return false;
    }

    SendResult send(SendRequest request);
}
