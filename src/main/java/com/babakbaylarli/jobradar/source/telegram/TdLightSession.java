package com.babakbaylarli.jobradar.source.telegram;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one Telegram client session of the owner's own account, shared by the channel reader and the direct-message
 * sender. It is created lazily on first use (so having it as a bean costs nothing), never prompts for a login code
 * (the one-time login is {@code --telegram-login}) and is closed when the application stops.
 */
@Component
public class TdLightSession {

    private static final Logger log = LoggerFactory.getLogger(TdLightSession.class);

    private final JobRadarProperties.TelegramChannels config;
    private SimpleTelegramClientFactory factory;
    private SimpleTelegramClient client;

    public TdLightSession(JobRadarProperties properties) {
        this.config = properties.sources().telegramChannels();
    }

    public boolean credentialsPresent() {
        return config.credentialsPresent();
    }

    public synchronized SimpleTelegramClient client() throws ChannelReaderException {
        if (client != null) {
            return client;
        }
        if (!config.credentialsPresent()) {
            throw new ChannelReaderException("TELEGRAM_API_ID / TELEGRAM_API_HASH / TELEGRAM_PHONE are not set");
        }
        TdLightSupport.initNatives();
        var settings = TdLightSupport.settings(config.apiId(), config.apiHash(), config.sessionDir());
        AtomicBoolean loginRequested = new AtomicBoolean();
        factory = TdLightSupport.newFactory();
        var builder = factory.builder(settings);
        // Non-interactive: if TDLib asks for a code or password, the session is not logged in. Fail instead of waiting.
        builder.setClientInteraction((parameter, info) -> {
            loginRequested.set(true);
            return CompletableFuture.failedFuture(new IllegalStateException("login required: " + parameter));
        });
        SimpleTelegramClient created = builder.build(AuthenticationSupplier.user(config.phone().strip()));
        try {
            created.getMeAsync().get(60, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException | InterruptedException e) {
            closeClient(created);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ChannelReaderException(loginRequested.get()
                    ? "The Telegram session is not logged in. Run the one-time login: docker compose run --rm -it app --telegram-login"
                    : "Could not start the Telegram client: " + e.getMessage(), e);
        }
        client = created;
        log.info("event=telegram_client_ready");
        return client;
    }

    @jakarta.annotation.PreDestroy
    public synchronized void close() {
        closeClient(client);
        client = null;
        if (factory != null) {
            factory.close();
            factory = null;
        }
    }

    private static void closeClient(SimpleTelegramClient telegram) {
        if (telegram != null) {
            try {
                telegram.closeAndWait();
            } catch (Exception e) {
                log.debug("Error while closing the Telegram client: {}", e.getMessage());
            }
        }
    }
}
