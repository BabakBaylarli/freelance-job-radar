package com.babakbaylarli.jobradar.matching;

import java.util.List;

/**
 * Outcome of scoring one job. {@code matchedKeywords} explains <em>why</em> it scored; {@code excludedBy}
 * is set when an exclude keyword vetoed the job (the score is still reported for transparency).
 */
public record ScoreResult(int score, List<String> matchedKeywords, String excludedBy, boolean matched) {

    public ScoreResult {
        matchedKeywords = List.copyOf(matchedKeywords);
    }
}
