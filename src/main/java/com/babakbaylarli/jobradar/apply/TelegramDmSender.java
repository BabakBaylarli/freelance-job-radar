package com.babakbaylarli.jobradar.apply;

import com.babakbaylarli.jobradar.source.Job;
import com.babakbaylarli.jobradar.source.telegram.DirectMessageException;
import com.babakbaylarli.jobradar.source.telegram.TdLightSession;
import com.babakbaylarli.jobradar.source.telegram.TelegramDirectMessenger;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * For posts of public Telegram channels that name a contact {@code @username}: sends <b>one</b> direct message from the
 * owner's own account with the approved proposal. Never to bots, never a follow-up, flood waits are reported not retried.
 */
@Component
public class TelegramDmSender implements ProposalSender {

    private final TelegramDirectMessenger messenger;
    private final TdLightSession session;

    public TelegramDmSender(TelegramDirectMessenger messenger, TdLightSession session) {
        this.messenger = messenger;
        this.session = session;
    }

    @Override
    public String platform() {
        return "telegram";
    }

    @Override
    public String route() {
        return "Telegram message from your own account";
    }

    @Override
    public boolean supports(Job job) {
        return recipient(job).isPresent();
    }

    @Override
    public Optional<String> unavailableReason() {
        return session.credentialsPresent() ? Optional.empty()
                : Optional.of("TELEGRAM_API_ID / TELEGRAM_API_HASH / TELEGRAM_PHONE are not set (and --telegram-login done)");
    }

    @Override
    public Optional<String> recipient(Job job) {
        if (!job.source().startsWith("telegram:")) {
            return Optional.empty();
        }
        String channel = job.source().substring("telegram:".length());
        return ContactFinder.telegramUsername(job.title() + "\n" + job.description(), channel).map(name -> "@" + name);
    }

    @Override
    public SendResult send(SendRequest request) {
        if (request.recipient() == null || request.recipient().isBlank()) {
            return SendResult.failed("No contact @username was found in this post.");
        }
        try {
            return SendResult.ok(messenger.send(request.recipient().replaceFirst("^@", ""), request.text()));
        } catch (DirectMessageException e) {
            return SendResult.failed(e.getMessage());
        }
    }
}
