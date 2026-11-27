package com.babakbaylarli.jobradar.matching;

import com.babakbaylarli.jobradar.source.Job;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Cross-source identity of a posting: normalized title plus the company (or, for platforms that hide the client,
 * the budget). Two jobs with the same key seen within a few days are treated as the same job posted on two platforms.
 */
public final class DedupKey {

    private static final int MIN_TITLE_LENGTH = 10;

    private DedupKey() {}

    /** @return the key, or {@code null} when the job carries too little information to be matched safely. */
    public static String of(Job job) {
        String title = normalize(job.title());
        if (title.length() < MIN_TITLE_LENGTH) {
            return null;
        }
        String company = normalize(job.company());
        if (!company.isEmpty()) {
            return title + "|" + company;
        }
        if (job.budget() != null && job.budget().ceiling() != null) {
            return title + "|" + job.budget().ceiling().stripTrailingZeros().toPlainString() + (job.budget().currency() == null ? "" : job.budget().currency());
        }
        return null;
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        return folded.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
