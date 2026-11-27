package com.babakbaylarli.jobradar.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.babakbaylarli.jobradar.source.Job;
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
        return new Job(source, externalId, title, company, url, tags, description, salary, postedAt);
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
