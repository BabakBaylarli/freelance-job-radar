package com.babakbaylarli.jobradar.source.telegram;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TelegramError;
import it.tdlight.jni.TdApi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads public channels through the official Telegram client API (MTProto, TDLib) as the owner's own account. It only
 * resolves a public username and reads the message history: it never joins a channel, never posts, never sends. The
 * session must have been created once with {@code --telegram-login}; when it is missing this fails with a clear message
 * instead of waiting for a verification code that nobody can type.
 */
public class TdLightChannelReader implements ChannelReader {

    private static final Logger log = LoggerFactory.getLogger(TdLightChannelReader.class);
    private static final long TIMEOUT_SECONDS = 30;

    private final JobRadarProperties.TelegramChannels config;
    private SimpleTelegramClientFactory factory;
    private SimpleTelegramClient client;

    public TdLightChannelReader(JobRadarProperties.TelegramChannels config) {
        this.config = config;
    }

    @Override
    public synchronized List<ChannelPost> latestPosts(String username, int limit) throws ChannelReaderException {
        SimpleTelegramClient telegram = client();
        try {
            TdApi.Chat chat = telegram.send(new TdApi.SearchPublicChat(username)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!(chat.type instanceof TdApi.ChatTypeSupergroup)) {
                // a username of a person or bot would resolve to a private chat: never read those
                throw new ChannelReaderException("@" + username + " is not a public channel or group");
            }
            TdApi.Messages history = telegram.send(new TdApi.GetChatHistory(chat.id, 0, 0, Math.min(Math.max(limit, 1), 100), false))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<ChannelPost> posts = new ArrayList<>();
            for (TdApi.Message message : history.messages) {
                String text = message == null ? null : textOf(message.content);
                if (text != null && !text.isBlank()) {
                    posts.add(new ChannelPost(username, publicMessageId(message.id), text.strip(), Instant.ofEpochSecond(message.date)));
                }
            }
            return posts;
        } catch (ExecutionException e) {
            throw translate(username, e.getCause());
        } catch (TimeoutException e) {
            throw new ChannelReaderException("Telegram did not answer in time for @" + username);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelReaderException("Interrupted while reading @" + username);
        }
    }

    private ChannelReaderException translate(String username, Throwable cause) {
        if (cause instanceof TelegramError error) {
            if (error.getErrorCode() == 429) {
                return new ChannelReaderException("Telegram flood wait while reading @" + username + ": " + error.getErrorMessage(), cause);
            }
            return new ChannelReaderException("Telegram error %d for @%s: %s".formatted(error.getErrorCode(), username, error.getErrorMessage()), cause);
        }
        return new ChannelReaderException("Reading @" + username + " failed: " + cause, cause);
    }

    private SimpleTelegramClient client() throws ChannelReaderException {
        if (client != null) {
            return client;
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

    @Override
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

    // ---- pure helpers (tested without a Telegram connection) ----------------------------------------------------

    /** TDLib message ids are the public message id shifted left by 20 bits. */
    static long publicMessageId(long tdlibMessageId) {
        return tdlibMessageId >> 20;
    }

    static String textOf(TdApi.MessageContent content) {
        if (content instanceof TdApi.MessageText text) {
            return text.text == null ? null : text.text.text;
        }
        TdApi.FormattedText caption = null;
        if (content instanceof TdApi.MessagePhoto photo) {
            caption = photo.caption;
        } else if (content instanceof TdApi.MessageVideo video) {
            caption = video.caption;
        } else if (content instanceof TdApi.MessageDocument document) {
            caption = document.caption;
        } else if (content instanceof TdApi.MessageAnimation animation) {
            caption = animation.caption;
        }
        return caption == null ? null : caption.text;
    }
}
