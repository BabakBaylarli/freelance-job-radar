package com.babakbaylarli.jobradar.bot;

import com.babakbaylarli.jobradar.persistence.BotStateEntity;
import com.babakbaylarli.jobradar.persistence.BotStateRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Access to the persisted pause flag and last digest time. */
@Service
public class BotStateService {

    private final BotStateRepository repository;

    public BotStateService(BotStateRepository repository) {
        this.repository = repository;
    }

    public boolean isPaused() {
        return load().isPaused();
    }

    public void setPaused(boolean paused) {
        BotStateEntity state = load();
        state.setPaused(paused);
        repository.save(state);
    }

    public Optional<Instant> lastDigestAt() {
        return Optional.ofNullable(load().getLastDigestAt());
    }

    public void recordDigest(Instant at) {
        BotStateEntity state = load();
        state.setLastDigestAt(at);
        repository.save(state);
    }

    private BotStateEntity load() {
        return repository.findById(BotStateEntity.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("bot_state row missing - did the Flyway migration run?"));
    }
}
