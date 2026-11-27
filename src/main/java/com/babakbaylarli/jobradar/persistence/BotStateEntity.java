package com.babakbaylarli.jobradar.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The single row holding the bot's runtime state ({@code /pause}, last digest time). */
@Entity
@Table(name = "bot_state")
public class BotStateEntity {

    public static final short SINGLETON_ID = 1;

    @Id
    private short id = SINGLETON_ID;

    @Column(nullable = false)
    private boolean paused;

    @Column(name = "last_digest_at")
    private Instant lastDigestAt;

    protected BotStateEntity() {}

    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }
    public Instant getLastDigestAt() { return lastDigestAt; }
    public void setLastDigestAt(Instant lastDigestAt) { this.lastDigestAt = lastDigestAt; }
}
