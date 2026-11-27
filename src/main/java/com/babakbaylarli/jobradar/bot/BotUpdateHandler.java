package com.babakbaylarli.jobradar.bot;

import com.babakbaylarli.jobradar.ai.ProposalService;
import com.babakbaylarli.jobradar.apply.ApplicationService;
import com.babakbaylarli.jobradar.apply.ApplicationService.SendDecision;
import com.babakbaylarli.jobradar.apply.ApplyException;
import com.babakbaylarli.jobradar.persistence.ApplicationEntity;
import com.babakbaylarli.jobradar.persistence.ApplicationStatus;
import com.babakbaylarli.jobradar.ai.ProposalService.Outcome;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.persistence.JobEntity;
import com.babakbaylarli.jobradar.persistence.JobRepository;
import com.babakbaylarli.jobradar.persistence.SourceStateEntity;
import com.babakbaylarli.jobradar.persistence.SourceStateRepository;
import com.babakbaylarli.jobradar.persistence.UserStatus;
import com.babakbaylarli.jobradar.source.JobSource;
import com.babakbaylarli.jobradar.source.Market;
import com.babakbaylarli.jobradar.source.SourceCatalog;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    private final ApplicationService applications;
    private final MessageFormatter formatter;
    private final List<JobSource> sources;
    private final SourceCatalog catalog;
    private final Clock clock;
    private final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Long> drafting = ConcurrentHashMap.newKeySet();

    public BotUpdateHandler(TelegramGateway gateway, JobRadarProperties properties, JobRepository jobs,
            SourceStateRepository sourceStates, BotStateService state, NotificationService notifications,
            ProposalService proposals, ApplicationService applications, MessageFormatter formatter, List<JobSource> sources, SourceCatalog catalog, Clock clock) {
        this.gateway = gateway;
        this.properties = properties;
        this.jobs = jobs;
        this.sourceStates = sourceStates;
        this.state = state;
        this.notifications = notifications;
        this.proposals = proposals;
        this.applications = applications;
        this.formatter = formatter;
        this.sources = sources;
        this.catalog = catalog;
        this.clock = clock;
    }

    // ---- entry points -----------------------------------------------------------------------------------------

    public void onMessage(long chatId, String text) {
        onMessage(chatId, text, null);
    }

    /** @param replyToMessageId the message this one replies to (e.g. the draft the owner answers "ok" to), or null */
    public void onMessage(long chatId, String text, Long replyToMessageId) {
        if (!authorizeMessage(chatId)) {
            return;
        }
        String trimmed = text == null ? "" : text.strip();
        if (!trimmed.startsWith("/")) {
            plainText(chatId, trimmed, replyToMessageId);
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
            case "/sources" -> reply(chatId, sources());
            case "/applied" -> reply(chatId, formatter.applied(applications.recent(20), id -> jobs.findById(id)));
            case "/limits" -> reply(chatId, formatter.limits(applications.limits(), clock.instant()));
            case "/markets" -> {
                Set<Market> enabled = state.enabledMarkets();
                gateway.sendHtml(chatId, formatter.markets(enabled), formatter.marketsKeyboard(enabled));
            }
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
        if (parts.length == 2 && MessageFormatter.MARKET.equals(parts[0])) {
            toggleMarket(chatId, messageId, callbackId, parts[1]);
            return;
        }
        if (parts.length == 2 && isApplicationAction(parts[0])) {
            applicationAction(chatId, messageId, callbackId, parts[0], parseId(parts[1]));
            return;
        }
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

    private void toggleMarket(long chatId, int messageId, String callbackId, String name) {
        Market market;
        try {
            market = Market.valueOf(name);
        } catch (IllegalArgumentException e) {
            gateway.answerCallback(callbackId, "Unknown market");
            return;
        }
        Set<Market> enabled = state.toggleMarket(market);
        gateway.answerCallback(callbackId, market.flag() + " " + market.label() + ": " + (enabled.contains(market) ? "on" : "off"));
        gateway.editKeyboard(chatId, messageId, formatter.marketsKeyboard(enabled));
    }

    private String sources() {
        Map<String, SourceStateEntity> states = sourceStates.findAll().stream()
                .collect(Collectors.toMap(SourceStateEntity::getSource, Function.identity()));
        var since = clock.instant().minus(Duration.ofHours(24));
        Map<String, Long> counts = new java.util.HashMap<>();
        catalog.entries().forEach(e -> counts.put(e.id(), jobs.countFromSourceSince(e.id(), since)));
        return formatter.sources(catalog.entries(), states, counts, clock.instant());
    }

    /** "✍️ Draft proposal": drafts and shows the application; nothing is sent. */
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
                showDraft(chatId, applications.startDraft(job));
            } catch (ApplyException e) {
                reply(chatId, "⚠️ " + MessageFormatter.escape(e.getMessage()));
            } catch (RuntimeException e) {
                log.error("event=draft_failed job_id={}", job.getId(), e);
                reply(chatId, "⚠️ Could not draft a proposal right now. Please try again.");
            } finally {
                drafting.remove(job.getId());
            }
        });
    }

    // ---- one-tap apply ------------------------------------------------------------------------------------------

    private static final java.util.Set<String> OK_WORDS = java.util.Set.of("ok", "okay", "oke", "okey", "ок", "окей", "оке", "tamam");

    /** "ok", "Ok!", "ОК", "tamam" - the owner's approval word. */
    static boolean isApproval(String text) {
        return OK_WORDS.contains(text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", ""));
    }

    private static boolean isApplicationAction(String prefix) {
        return List.of(MessageFormatter.APP_SEND, MessageFormatter.APP_EDIT, MessageFormatter.APP_REGENERATE, MessageFormatter.APP_CANCEL,
                MessageFormatter.APP_YES, MessageFormatter.APP_NO, MessageFormatter.APP_COPY).contains(prefix);
    }

    /** A message that is not a command: an "ok" reply to a draft, or the edit the owner was asked for. */
    private void plainText(long chatId, String text, Long replyTo) {
        try {
            Optional<ApplicationEntity> replied = replyTo == null ? Optional.empty() : applications.findByDraftMessage(replyTo);
            if (replied.isPresent() && isApproval(text)) {
                ApplicationEntity app = replied.get();
                switch (app.getStatus()) {
                    case DRAFT, FAILED -> showDecision(chatId, applications.requestSend(app.getId()));
                    case CONFIRMING -> showDecision(chatId, applications.confirmSend(app.getId()));
                    case EDITING -> reply(chatId, "You are still editing this draft. Send the edited text first, then approve it.");
                    default -> reply(chatId, "That application is already " + app.getStatus().name().toLowerCase() + ".");
                }
                return;
            }
            Optional<ApplicationEntity> editing = replied.filter(a -> a.getStatus() == ApplicationStatus.EDITING).or(applications::editing);
            if (editing.isPresent()) {
                showDraft(chatId, applications.applyEdit(editing.get().getId(), text));
                return;
            }
            reply(chatId, replied.isPresent() ? "To change this draft tap ✏️ Edit first. To send it, reply <b>ok</b>."
                    : "I only understand commands. Try /help.");
        } catch (ApplyException e) {
            reply(chatId, "⚠️ " + MessageFormatter.escape(e.getMessage()));
        }
    }

    private void applicationAction(long chatId, int messageId, String callbackId, String action, Long applicationId) {
        if (applicationId == null) {
            gateway.answerCallback(callbackId, "Unknown application");
            return;
        }
        try {
            switch (action) {
                case MessageFormatter.APP_SEND -> {
                    gateway.answerCallback(callbackId, "Checking…");
                    showDecision(chatId, applications.requestSend(applicationId));
                }
                case MessageFormatter.APP_YES -> {
                    gateway.answerCallback(callbackId, "Sending…");
                    showDecision(chatId, applications.confirmSend(applicationId));
                }
                case MessageFormatter.APP_NO -> {
                    applications.declineConfirmation(applicationId);
                    gateway.answerCallback(callbackId, "Not sent");
                    gateway.editKeyboard(chatId, messageId, Keyboard.empty());
                    reply(chatId, "👍 Not sent. The draft is still there (tap ✍️ Draft proposal on the job to see it again).");
                }
                case MessageFormatter.APP_EDIT -> {
                    applications.beginEdit(applicationId);
                    gateway.answerCallback(callbackId, "Edit mode");
                    reply(chatId, formatter.editPrompt());
                }
                case MessageFormatter.APP_REGENERATE -> {
                    gateway.answerCallback(callbackId, "Regenerating… 🔁");
                    async.execute(() -> {
                        try {
                            showDraft(chatId, applications.regenerate(applicationId));
                        } catch (ApplyException e) {
                            reply(chatId, "⚠️ " + MessageFormatter.escape(e.getMessage()));
                        } catch (RuntimeException e) {
                            log.error("event=regenerate_failed application_id={}", applicationId, e);
                            reply(chatId, "⚠️ Could not regenerate right now. Please try again.");
                        }
                    });
                }
                case MessageFormatter.APP_CANCEL -> {
                    applications.cancel(applicationId);
                    gateway.answerCallback(callbackId, "Cancelled");
                    gateway.editKeyboard(chatId, messageId, Keyboard.empty());
                }
                case MessageFormatter.APP_COPY -> {
                    ApplicationEntity app = applications.copyAndOpen(applicationId);
                    gateway.answerCallback(callbackId, "Copy ready 📋");
                    gateway.editKeyboard(chatId, messageId, Keyboard.empty());
                    reply(chatId, formatter.copyReady(app, applications.jobOf(app)));
                }
                default -> gateway.answerCallback(callbackId, "Unknown action");
            }
        } catch (ApplyException e) {
            gateway.answerCallback(callbackId, "Not possible");
            reply(chatId, "⚠️ " + MessageFormatter.escape(e.getMessage()));
        }
    }

    private void showDraft(long chatId, ApplicationEntity app) {
        JobEntity job = applications.jobOf(app);
        Optional<com.babakbaylarli.jobradar.apply.ProposalSender> route = applications.routeOf(app);
        long messageId = gateway.sendHtml(chatId, formatter.draft(app, job, route, applications.missingSetupFor(job)),
                formatter.draftKeyboard(app, route.isPresent()));
        applications.attachDraftMessage(app.getId(), messageId);
    }

    private void showDecision(long chatId, SendDecision decision) {
        switch (decision) {
            case SendDecision.NeedsConfirmation needs -> {
                long messageId = gateway.sendHtml(chatId, formatter.confirmation(needs.application(), needs.note()), formatter.confirmationKeyboard(needs.application()));
                applications.attachDraftMessage(needs.application().getId(), messageId); // replying "ok" to this message confirms
            }
            case SendDecision.Sent sent -> reply(chatId, formatter.sent(sent.application(), applications.jobOf(sent.application()), applications.routeOf(sent.application())));
            case SendDecision.Failed failed -> gateway.sendHtml(chatId, formatter.failed(failed.application(), failed.reason()), formatter.failedKeyboard(failed.application()));
            case SendDecision.Blocked blocked -> reply(chatId, formatter.blocked(blocked.reason()));
        }
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
        return formatter.stats(sources.stream().map(JobSource::id).toList(), jobs.statsSince(since), jobs.marketStatsSince(since),
                states, clock.instant());
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
