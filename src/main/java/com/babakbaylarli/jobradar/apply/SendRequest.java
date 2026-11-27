package com.babakbaylarli.jobradar.apply;

import com.babakbaylarli.jobradar.source.Job;

/** Everything a {@link ProposalSender} needs. {@code bid} is null for platforms without bids; {@code recipient} for those without a contact. */
public record SendRequest(Job job, String text, Bid bid, String recipient) {}
