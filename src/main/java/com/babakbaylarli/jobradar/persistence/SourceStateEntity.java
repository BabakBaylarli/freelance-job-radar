package com.babakbaylarli.jobradar.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "source_state")
public class SourceStateEntity {

    @Id
    private String source;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_status")
    private String lastStatus;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "last_fetched", nullable = false)
    private int lastFetched;

    @Column(name = "last_new", nullable = false)
    private int lastNew;

    protected SourceStateEntity() {}

    public SourceStateEntity(String source) {
        this.source = source;
    }

    public void recordSuccess(Instant at, int fetched, int created) {
        this.lastPolledAt = at;
        this.lastSuccessAt = at;
        this.lastStatus = "OK";
        this.lastError = null;
        this.lastFetched = fetched;
        this.lastNew = created;
    }

    public void recordFailure(Instant at, String error) {
        this.lastPolledAt = at;
        this.lastStatus = "FAILED";
        this.lastError = error;
    }

    public String getSource() { return source; }
    public Instant getLastPolledAt() { return lastPolledAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public String getLastStatus() { return lastStatus; }
    public String getLastError() { return lastError; }
    public int getLastFetched() { return lastFetched; }
    public int getLastNew() { return lastNew; }
}
