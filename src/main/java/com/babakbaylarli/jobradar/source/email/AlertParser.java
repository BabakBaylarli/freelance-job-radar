package com.babakbaylarli.jobradar.source.email;

import com.babakbaylarli.jobradar.source.Job;
import java.util.List;

/** Extracts the jobs from the alert e-mails of one known sender. */
public interface AlertParser {

    /** Short platform id used in the stored source name, e.g. {@code upwork} (stored as {@code email:upwork}). */
    String platform();

    boolean supports(EmailMessageData mail);

    List<Job> parse(EmailMessageData mail);
}
