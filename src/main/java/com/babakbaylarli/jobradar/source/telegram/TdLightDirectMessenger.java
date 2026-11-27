package com.babakbaylarli.jobradar.source.telegram;

import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.TelegramError;
import it.tdlight.jni.TdApi;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One direct message to one person, through Telegram's official client API. Rules: never to bots, only to a private chat
 * with a real user the username resolves to, a single message (no follow-ups), and a Telegram flood-wait answer is reported
 * with its wait time instead of being retried.
 */
@Component
public class TdLightDirectMessenger implements TelegramDirectMessenger {

    private static final Logger log = LoggerFactory.getLogger(TdLightDirectMessenger.class);
    private static final long TIMEOUT_SECONDS = 30;

    private final TdLightSession session;

    public TdLightDirectMessenger(TdLightSession session) {
        this.session = session;
    }

    @Override
    public synchronized String send(String username, String text) throws DirectMessageException {
        SimpleTelegramClient client;
        try {
            client = session.client();
        } catch (ChannelReaderException e) {
            throw new DirectMessageException(e.getMessage(), e);
        }
        try {
            TdApi.Chat chat = client.send(new TdApi.SearchPublicChat(username)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!(chat.type instanceof TdApi.ChatTypePrivate privateChat)) {
                throw new DirectMessageException("@" + username + " is not a person's account (it is a channel or group); no message sent");
            }
            TdApi.User user = client.send(new TdApi.GetUser(privateChat.userId)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (user.type instanceof TdApi.UserTypeBot) {
                throw new DirectMessageException("@" + username + " is a bot; the bot never messages bots");
            }
            TdApi.SendMessage request = new TdApi.SendMessage();
            request.chatId = chat.id;
            request.inputMessageContent = new TdApi.InputMessageText(new TdApi.FormattedText(text, new TdApi.TextEntity[0]), null, false);
            TdApi.Message sent = client.send(request).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("event=application_sent platform=telegram");
            return String.valueOf(sent.id >> 20);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TelegramError error) {
                throw new DirectMessageException(error.getErrorCode() == 429
                        ? "Telegram asks to wait before sending again (flood wait): " + error.getErrorMessage()
                        : "Telegram error %d: %s".formatted(error.getErrorCode(), error.getErrorMessage()), e);
            }
            throw new DirectMessageException("Sending failed: " + e.getCause(), e);
        } catch (TimeoutException e) {
            throw new DirectMessageException("Telegram did not answer in time; the message may or may not have been sent - check before retrying");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DirectMessageException("Interrupted while sending");
        }
    }
}
