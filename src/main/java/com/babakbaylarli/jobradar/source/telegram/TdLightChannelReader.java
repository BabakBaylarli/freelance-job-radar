package com.babakbaylarli.jobradar.source.telegram;

import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.TelegramError;
import it.tdlight.jni.TdApi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    private final TdLightSession session;

    public TdLightChannelReader(TdLightSession session) {
        this.session = session;
    }

    @Override
    public synchronized List<ChannelPost> latestPosts(String username, int limit) throws ChannelReaderException {
        SimpleTelegramClient telegram = session.client();
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

    @Override
    public void close() {
        // the shared TdLightSession is closed by Spring
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
