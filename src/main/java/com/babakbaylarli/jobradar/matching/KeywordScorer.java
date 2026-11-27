package com.babakbaylarli.jobradar.matching;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.source.Job;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Scores a job against the weighted include keywords and the exclude keywords from the YAML config.
 * <ul>
 *   <li>Keywords match case-insensitively on whole terms in title + tags + description
 *       ({@code java} does not match {@code javascript}; {@code c++} and {@code spring boot} work).</li>
 *   <li>Each include keyword counts once, however often it appears.</li>
 *   <li>Any exclude keyword vetoes the job.</li>
 *   <li>A job is a match when it is not excluded and {@code score >= min-score}.</li>
 * </ul>
 */
@Component
public class KeywordScorer {

    private record Include(String term, int weight, Pattern pattern) {}

    private record Exclude(String term, Pattern pattern) {}

    private final List<Include> includes;
    private final List<Exclude> excludes;
    private final int minScore;

    public KeywordScorer(JobRadarProperties properties) {
        this(properties.matching());
    }

    public KeywordScorer(JobRadarProperties.Matching matching) {
        this.includes = matching.include().stream()
                .filter(k -> k.term() != null && !k.term().isBlank())
                .map(k -> new Include(k.term().strip(), k.weight(), wholeTerm(k.term())))
                .toList();
        this.excludes = matching.exclude().stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> new Exclude(t.strip(), wholeTerm(t)))
                .toList();
        this.minScore = matching.minScore();
    }

    public ScoreResult score(Job job) {
        String haystack = job.title() + "\n" + String.join("\n", job.tags()) + "\n" + job.description();
        int score = 0;
        List<String> matched = new ArrayList<>();
        for (Include include : includes) {
            if (include.pattern().matcher(haystack).find()) {
                score += include.weight();
                matched.add(include.term().toLowerCase(Locale.ROOT));
            }
        }
        String excludedBy = excludes.stream()
                .filter(e -> e.pattern().matcher(haystack).find())
                .map(Exclude::term)
                .findFirst()
                .orElse(null);
        return new ScoreResult(score, matched, excludedBy, excludedBy == null && score >= minScore);
    }

    public int minScore() {
        return minScore;
    }

    private static Pattern wholeTerm(String term) {
        return Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(term.strip()) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
