package com.babakbaylarli.jobradar.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "applications")
public class ApplicationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(nullable = false)
    private String platform;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status = ApplicationStatus.DRAFT;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "bid_amount")
    private BigDecimal bidAmount;

    @Column(name = "bid_currency")
    private String bidCurrency;

    @Column(name = "bid_days")
    private Integer bidDays;

    private String recipient;

    @Column(name = "draft_message_id")
    private Long draftMessageId;

    @Column(name = "response_id")
    private String responseId;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected ApplicationEntity() {}

    public ApplicationEntity(Long jobId, String platform, String text, BigDecimal bidAmount, String bidCurrency, Integer bidDays,
            String recipient, Instant now) {
        this.jobId = jobId;
        this.platform = platform;
        this.text = text;
        this.bidAmount = bidAmount;
        this.bidCurrency = bidCurrency;
        this.bidDays = bidDays;
        this.recipient = recipient;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Move to another state; illegal moves (e.g. SENT -> DRAFT) are rejected. */
    public void moveTo(ApplicationStatus next, Instant now) {
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException("Illegal application state change %s -> %s".formatted(status, next));
        }
        this.status = next;
        this.updatedAt = now;
    }

    public void markSent(String responseId, Instant now) {
        moveTo(ApplicationStatus.SENT, now);
        this.responseId = responseId;
        this.error = null;
        this.sentAt = now;
    }

    public void markFailed(String error, Instant now) {
        moveTo(ApplicationStatus.FAILED, now);
        this.error = error;
    }

    public void changeText(String newText, Instant now) {
        this.text = newText;
        this.updatedAt = now;
    }

    public void changeBid(BigDecimal amount, Integer days, Instant now) {
        this.bidAmount = amount;
        this.bidDays = days;
        this.updatedAt = now;
    }

    public void setDraftMessageId(Long draftMessageId) { this.draftMessageId = draftMessageId; }

    public Long getId() { return id; }
    public Long getJobId() { return jobId; }
    public String getPlatform() { return platform; }
    public ApplicationStatus getStatus() { return status; }
    public String getText() { return text; }
    public BigDecimal getBidAmount() { return bidAmount; }
    public String getBidCurrency() { return bidCurrency; }
    public Integer getBidDays() { return bidDays; }
    public String getRecipient() { return recipient; }
    public Long getDraftMessageId() { return draftMessageId; }
    public String getResponseId() { return responseId; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getSentAt() { return sentAt; }
}
