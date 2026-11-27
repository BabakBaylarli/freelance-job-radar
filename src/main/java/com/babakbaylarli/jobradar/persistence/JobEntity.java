package com.babakbaylarli.jobradar.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.babakbaylarli.jobradar.source.Budget;
import com.babakbaylarli.jobradar.source.Job;
import com.babakbaylarli.jobradar.source.JobType;
import com.babakbaylarli.jobradar.source.Market;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "jobs")
public class JobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String source;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String company = "";

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> tags = new ArrayList<>();

    @Column(nullable = false, columnDefinition = "text")
    private String description = "";

    private String salary;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(nullable = false)
    private int score;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "matched_keywords", columnDefinition = "text[]", nullable = false)
    private List<String> matchedKeywords = new ArrayList<>();

    @Column(name = "excluded_by")
    private String excludedBy;

    @Column(nullable = false)
    private boolean matched;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Delivery delivery = Delivery.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_status", nullable = false)
    private UserStatus userStatus = UserStatus.NONE;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Market market = Market.INTERNATIONAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false)
    private JobType jobType = JobType.REMOTE_JOB;

    @Column(name = "budget_min")
    private BigDecimal budgetMin;

    @Column(name = "budget_max")
    private BigDecimal budgetMax;

    private String currency;

    @Column(nullable = false)
    private boolean hourly;

    @Column(name = "dedup_key", columnDefinition = "text")
    private String dedupKey;

    /** Other places this same job was seen, as "source|url". */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "also_on", columnDefinition = "text[]", nullable = false)
    private List<String> alsoOn = new ArrayList<>();

    protected JobEntity() {}

    public JobEntity(String source, String externalId, String url, String title, String company, List<String> tags,
            String description, String salary, Instant postedAt, Instant firstSeenAt, int score,
            List<String> matchedKeywords, String excludedBy, boolean matched, Delivery delivery) {
        this.source = source;
        this.externalId = externalId;
        this.url = url;
        this.title = title;
        this.company = company;
        this.tags = new ArrayList<>(tags);
        this.description = description;
        this.salary = salary;
        this.postedAt = postedAt;
        this.firstSeenAt = firstSeenAt;
        this.score = score;
        this.matchedKeywords = new ArrayList<>(matchedKeywords);
        this.excludedBy = excludedBy;
        this.matched = matched;
        this.delivery = delivery;
    }

    /** Full constructor for jobs coming from the ingest pipeline. */
    public JobEntity(Job job, String normalizedUrl, Instant firstSeenAt, int score, List<String> matchedKeywords,
            String excludedBy, boolean matched, Delivery delivery, String dedupKey) {
        this(job.source(), job.externalId(), normalizedUrl, job.title(), job.company(), job.tags(), job.description(),
                job.salary(), job.postedAt(), firstSeenAt, score, matchedKeywords, excludedBy, matched, delivery);
        this.market = job.market();
        this.jobType = job.type();
        Budget budget = job.budget();
        if (budget != null) {
            this.budgetMin = budget.min();
            this.budgetMax = budget.max();
            this.currency = budget.currency();
            this.hourly = budget.hourly();
        }
        this.dedupKey = dedupKey;
    }

    public Long getId() { return id; }
    public String getSource() { return source; }
    public String getExternalId() { return externalId; }
    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public String getCompany() { return company; }
    public List<String> getTags() { return tags; }
    public String getDescription() { return description; }
    public String getSalary() { return salary; }
    public Instant getPostedAt() { return postedAt; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public int getScore() { return score; }
    public List<String> getMatchedKeywords() { return matchedKeywords; }
    public String getExcludedBy() { return excludedBy; }
    public boolean isMatched() { return matched; }
    public Delivery getDelivery() { return delivery; }
    public UserStatus getUserStatus() { return userStatus; }
    public Instant getNotifiedAt() { return notifiedAt; }

    /** The normalized view of this job, as handed to the AI prompt builder. */
    public Job toJob() {
        Budget budget = budgetMin == null && budgetMax == null ? null : new Budget(budgetMin, budgetMax, currency, hourly);
        return new Job(source, externalId, title, company, url, tags, description, salary, postedAt, market, jobType, budget);
    }

    public Market getMarket() { return market; }
    public JobType getJobType() { return jobType; }
    public String getDedupKey() { return dedupKey; }
    public List<String> getAlsoOn() { return alsoOn; }
    public String getCurrency() { return currency; }

    /** Remember another place where this same job was posted. */
    public void addAlsoOn(String source, String url) {
        String entry = source + "|" + url;
        if (!alsoOn.contains(entry) && !url.equals(this.url)) {
            alsoOn.add(entry);
        }
    }

    public void markSent(Delivery how, Instant when) {
        this.delivery = how;
        this.notifiedAt = when;
    }

    public void defer() {
        this.delivery = Delivery.DEFERRED;
    }

    public void setUserStatus(UserStatus userStatus) {
        this.userStatus = userStatus;
    }
}
