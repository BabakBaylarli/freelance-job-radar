package com.babakbaylarli.jobradar.bot;

import com.babakbaylarli.jobradar.persistence.BotStateEntity;
import com.babakbaylarli.jobradar.persistence.BotStateRepository;
import com.babakbaylarli.jobradar.source.Market;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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

    /** Markets the owner currently wants notifications for. */
    public Set<Market> enabledMarkets() {
        Set<Market> enabled = EnumSet.allOf(Market.class);
        load().getDisabledMarkets().forEach(name -> {
            try {
                enabled.remove(Market.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // unknown name from an older/newer version: ignore
            }
        });
        return enabled;
    }

    /** Switch one market on or off and return the resulting set of enabled markets. */
    public Set<Market> toggleMarket(Market market) {
        BotStateEntity state = load();
        List<String> disabled = new ArrayList<>(state.getDisabledMarkets());
        if (!disabled.remove(market.name())) {
            disabled.add(market.name());
        }
        state.getDisabledMarkets().clear();
        state.getDisabledMarkets().addAll(disabled);
        repository.save(state);
        return enabledMarkets();
    }

    private BotStateEntity load() {
        return repository.findById(BotStateEntity.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("bot_state row missing - did the Flyway migration run?"));
    }
}
