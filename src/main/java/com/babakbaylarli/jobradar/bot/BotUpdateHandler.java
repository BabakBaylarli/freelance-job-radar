package com.babakbaylarli.jobradar.bot;

import com.babakbaylarli.jobradar.ai.ProposalService;
import com.babakbaylarli.jobradar.ai.ProposalService.Outcome;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.persistence.JobEntity;
import com.babakbaylarli.jobradar.persistence.JobRepository;
import com.babakbaylarli.jobradar.persistence.SourceStateEntity;
import com.babakbaylarli.jobradar.persistence.SourceStateRepository;
import com.babakbaylarli.jobradar.persistence.UserStatus;
import com.babakbaylarli.jobradar.source.JobSource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * All bot behaviour, independent of the Telegram library: commands and button presses come in as plain values.
 * Only the configured owner may use the bot; everyone else gets a polite "private bot" reply.
 */
@Component
public class BotUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(BotUpdateHandler.class);
    static final String PRIVATE_BOT = "🔒 Sorry, this is a private bot. If you need something like it, the source code is open - see the project page.";
    private static final int SAVED_LIMIT = 20;

    private final TelegramGateway gateway;
    private final JobRadarProperties properties;
    private final JobRepository jobs;
    private final SourceStateRepository sourceStates;
    private final BotStateService state;
    private final NotificationService notifications;
    private final ProposalService proposals;
    private final MessageFormatter formatter;
    private final List<JobSource> sources;
    private final Clock clock;
    private final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Long> drafting = ConcurrentHashMap.newKeySet();

    public BotUpdateHandler(TelegramGateway gateway, JobRadarProperties properties, JobRepository jobs,
            SourceStateRepository sourceStates, BotStateService state, NotificationService notifications,
            ProposalService proposals, MessageFormatter formatter, List<JobSource> sources, Clock clock) {
        this.gateway = gateway;
        this.properties = properties;
        this.jobs = jobs;
        this.sourceStates = sourceStates;
        this.state = state;
        this.notifications = notifications;
        this.proposals = proposals;
        this.formatter = formatter;
        this.sources = sources;
        this.clock = clock;
    }

    // ---- entry points -----------------------------------------------------------------------------------------

    public void onMessage(long chatId, String text) {
        if (!authorizeMessage(chatId)) {
            return;
        }
        String trimmed = text == null ? "" : text.strip();
        if (!trimmed.startsWith("/")) {
            reply(chatId, "I only understand commands. Try /help.");
            return;
        }
        String command = trimmed.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int at = command.indexOf('@');
        if (at > 0) {
            command = command.substring(0, at);
        }
        switch (command) {
            case "/start" -> reply(chatId, formatter.welcome(state.isPaused(), proposals.aiEnabled()));
            case "/help" -> reply(chatId, formatter.help());
            case "/keywords" -> reply(chatId, formatter.keywords(properties, sources.stream().map(JobSource::displayName).toList()));
            case "/saved" -> reply(chatId, formatter.saved(jobs.findByUserStatusOrderByIdDesc(UserStatus.SAVED, PageRequest.of(0, SAVED_LIMIT))));
            case "/stats" -> reply(chatId, stats());
            case "/pause" -> {
                state.setPaused(true);
                reply(chatId, "⏸ Notifications paused. I keep collecting jobs; send /resume to continue or /digest to peek.");
            }
            case "/resume" -> {
                state.setPaused(false);
                reply(chatId, "▶️ Notifications resumed.");
                async.execute(notifications::scheduledFlush);
            }
            case "/digest" -> {
                if (notifications.sendDigestNow() == 0) {
                    reply(chatId, "📭 No unseen matches right now. I'll message you when something new matches.");
                }
            }
            default -> reply(chatId, "Unknown command. Try /help.");
        }
    }

    public void onCallback(long chatId, int messageId, String callbackId, String data) {
        Long owner = properties.telegram().ownerChatId();
        if (owner == null || owner != chatId) {
            gateway.answerCallback(callbackId, "Private bot");
            return;
        }
        String[] parts = data == null ? new String[0] : data.split(":", 2);
        Long jobId = parts.length == 2 ? parseId(parts[1]) : null;
        JobEntity job = jobId == null ? null : jobs.findById(jobId).orElse(null);
        if (job == null) {
            gateway.answerCallback(callbackId, "That job is no longer available.");
            return;
        }
        switch (parts[0]) {
            case MessageFormatter.DRAFT -> draft(chatId, callbackId, job);
            case MessageFormatter.SAVE -> {
                job.setUserStatus(UserStatus.SAVED);
                jobs.save(job);
                gateway.answerCallback(callbackId, "Saved ⭐ — see /saved");
                refreshKeyboard(chatId, messageId, job, true);
            }
            case MessageFormatter.HIDE -> {
                job.setUserStatus(UserStatus.HIDDEN);
                jobs.save(job);
                gateway.answerCallback(callbackId, "Hidden 🙈");
                if (isLiveCard(job)) {
                    gateway.editKeyboard(chatId, messageId, Keyboard.empty());
                }
            }
            default -> gateway.answerCallback(callbackId, "Unknown action");
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private boolean authorizeMessage(long chatId) {
        Long owner = properties.telegram().ownerChatId();
        if (owner == null) {
            // Setup mode: help the owner discover their chat id; nothing else is exposed.
            reply(chatId, "👋 Setup mode. Your chat id is <code>%d</code>.\nPut it in <code>TELEGRAM_OWNER_CHAT_ID</code> "
                    .formatted(chatId) + "(your .env file) and restart the bot to activate it.");
            return false;
        }
        if (owner != chatId) {
            log.warn("event=unauthorized_chat chat_id={}", chatId);
            reply(chatId, PRIVATE_BOT);
            return false;
        }
        return true;
    }

    private void draft(long chatId, String callbackId, JobEntity job) {
        if (!proposals.aiEnabled()) {
            gateway.answerCallback(callbackId, "AI is disabled");
            reply(chatId, "🤖 <b>AI proposals are disabled.</b>\nSet <code>ANTHROPIC_API_KEY</code> in your .env file and restart "
                    + "(<code>docker compose up -d</code>) to enable the ✍️ Draft proposal button. Everything else keeps working.");
            return;
        }
        if (!drafting.add(job.getId())) {
            gateway.answerCallback(callbackId, "Already drafting this one…");
            return;
        }
        gateway.answerCallback(callbackId, "Drafting… ✍️");
        async.execute(() -> {
            try {
                switch (proposals.draft(job.toJob())) {
                    case Outcome.Drafted drafted -> reply(chatId, formatter.proposal(job, drafted.text()));
                    case Outcome.Disabled ignored -> reply(chatId, "🤖 AI proposals are disabled.");
                    case Outcome.Failed failed -> reply(chatId, "⚠️ Could not draft a proposal: " + MessageFormatter.escape(failed.message()));
                }
            } catch (RuntimeException e) {
                log.error("event=draft_failed job_id={}", job.getId(), e);
                reply(chatId, "⚠️ Could not draft a proposal right now. Please try again.");
            } finally {
                drafting.remove(job.getId());
            }
        });
    }

    private void refreshKeyboard(long chatId, int messageId, JobEntity job, boolean saved) {
        if (isLiveCard(job)) {
            gateway.editKeyboard(chatId, messageId, formatter.jobKeyboard(job, saved));
        }
    }

    /** Digest messages keep their keyboard (it serves several jobs); single-job cards get theirs updated. */
    private static boolean isLiveCard(JobEntity job) {
        return job.getDelivery() == com.babakbaylarli.jobradar.persistence.Delivery.SENT_LIVE;
    }

    private String stats() {
        Map<String, SourceStateEntity> states = sourceStates.findAll().stream()
                .collect(Collectors.toMap(SourceStateEntity::getSource, Function.identity()));
        var since = clock.instant().minus(Duration.ofDays(7));
        return formatter.stats(sources.stream().map(JobSource::id).toList(), jobs.statsSince(since), states, clock.instant());
    }

    private void reply(long chatId, String html) {
        try {
            gateway.sendHtml(chatId, html, null);
        } catch (TelegramSendException e) {
            log.warn("event=reply_failed error={}", e.getMessage());
        }
    }

    private static Long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
