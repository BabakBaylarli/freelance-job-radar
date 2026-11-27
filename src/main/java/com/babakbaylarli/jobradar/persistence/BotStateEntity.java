package com.babakbaylarli.jobradar.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "disabled_markets", columnDefinition = "text[]", nullable = false)
    private List<String> disabledMarkets = new ArrayList<>();

    protected BotStateEntity() {}

    public List<String> getDisabledMarkets() { return disabledMarkets; }

    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }
    public Instant getLastDigestAt() { return lastDigestAt; }
    public void setLastDigestAt(Instant lastDigestAt) { this.lastDigestAt = lastDigestAt; }
}
