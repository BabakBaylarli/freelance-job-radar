package com.babakbaylarli.jobradar.apply;

import com.babakbaylarli.jobradar.ai.ProposalService;
import com.babakbaylarli.jobradar.ai.ProposalService.Outcome;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.persistence.ApplicationEntity;
import com.babakbaylarli.jobradar.persistence.ApplicationRepository;
import com.babakbaylarli.jobradar.persistence.ApplicationStatus;
import com.babakbaylarli.jobradar.persistence.JobEntity;
import com.babakbaylarli.jobradar.persistence.JobRepository;
import com.babakbaylarli.jobradar.source.Job;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * The human-approved application flow. The one rule everything here serves: <b>nothing is ever sent unless the owner
 * explicitly approved that specific application</b>. The only methods that can reach a {@link ProposalSender} are
 * {@link #requestSend} and {@link #confirmSend}, and they are called only from the owner's own actions (the Send button, a
 * reply of "ok" to the draft, the final confirmation). There is no scheduler, no batch and no auto-apply path.
 * <p>
 * Safety rails: a daily limit (total and per platform), a minimum gap between sends, a database-enforced "never the same
 * job twice", and failed sends are shown, never silently retried.
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);
    private static final int INSTRUCTION_MAX_CHARS = 200;
    private static final int MIN_PROPOSAL_CHARS = 40;
    private static final Pattern BID_COMMAND = Pattern.compile("(?i)^\\s*bid\\s+(\\d+(?:[.,]\\d+)?)(?:\\s+(\\d{1,3}))?\\s*$");
    private static final List<ApplicationStatus> OPEN = List.of(ApplicationStatus.DRAFT, ApplicationStatus.EDITING, ApplicationStatus.CONFIRMING, ApplicationStatus.FAILED);

    public sealed interface SendDecision {
        /** Waiting for the owner's "yes" to a one-line warning (e.g. the bid uses one of the limited monthly bids). */
        record NeedsConfirmation(ApplicationEntity application, String note) implements SendDecision {}

        record Sent(ApplicationEntity application) implements SendDecision {}

        record Failed(ApplicationEntity application, String reason) implements SendDecision {}

        /** Not sent, on purpose: a limit, the minimum gap, or a duplicate. */
        record Blocked(ApplicationEntity application, String reason) implements SendDecision {}
    }

    public record LimitsView(long sentToday, int dailyLimit, Map<String, Long> sentPerPlatform, Map<String, Integer> platformLimits,
            Duration minGap, Optional<Instant> nextAllowed) {}

    private final ApplicationRepository applications;
    private final JobRepository jobs;
    private final ProposalService proposals;
    private final SenderRegistry senders;
    private final BidAdvisor bids;
    private final JobRadarProperties.Apply config;
    private final Clock clock;

    public ApplicationService(ApplicationRepository applications, JobRepository jobs, ProposalService proposals, SenderRegistry senders,
            BidAdvisor bids, JobRadarProperties properties, Clock clock) {
        this.applications = applications;
        this.jobs = jobs;
        this.proposals = proposals;
        this.senders = senders;
        this.bids = bids;
        this.config = properties.apply();
        this.clock = clock;
    }

    // ---- drafting -----------------------------------------------------------------------------------------------

    /** Draft (or return the open draft of) a job. Nothing is sent. */
    public synchronized ApplicationEntity startDraft(JobEntity jobEntity) {
        if (applications.existsByJobIdAndStatus(jobEntity.getId(), ApplicationStatus.SENT)) {
            throw new ApplyException("You already sent an application for this job.");
        }
        Optional<ApplicationEntity> open = applications.findFirstByJobIdAndStatusInOrderByIdDesc(jobEntity.getId(), OPEN);
        if (open.isPresent()) {
            return open.get();
        }
        Job job = jobEntity.toJob();
        String text = generate(job);
        Optional<ProposalSender> sender = senders.readySenderFor(job);
        Optional<Bid> bid = sender.filter(ProposalSender::needsBid).flatMap(s -> bids.suggest(job));
        ApplicationEntity application = new ApplicationEntity(jobEntity.getId(), sender.map(ProposalSender::platform).orElse("copy"), text,
                bid.map(Bid::amount).orElse(null), bid.map(Bid::currency).orElse(null), bid.map(Bid::days).orElse(null),
                sender.flatMap(s -> s.recipient(job)).orElse(null), clock.instant());
        return applications.save(application);
    }

    public synchronized ApplicationEntity regenerate(long id) {
        ApplicationEntity app = require(id, ApplicationStatus.DRAFT, ApplicationStatus.EDITING, ApplicationStatus.FAILED);
        if (app.getStatus() != ApplicationStatus.DRAFT) {
            app.moveTo(ApplicationStatus.DRAFT, clock.instant());
        }
        app.changeText(generate(jobOf(app).toJob()), clock.instant());
        return applications.save(app);
    }

    public synchronized ApplicationEntity beginEdit(long id) {
        ApplicationEntity app = require(id, ApplicationStatus.DRAFT, ApplicationStatus.FAILED);
        // one edit at a time: any other draft that was waiting for an edit goes back to being a plain draft
        applications.findByStatusInOrderByIdDesc(List.of(ApplicationStatus.EDITING), PageRequest.of(0, 20)).forEach(other -> {
            other.moveTo(ApplicationStatus.DRAFT, clock.instant());
            applications.save(other);
        });
        if (app.getStatus() == ApplicationStatus.FAILED) {
            app.moveTo(ApplicationStatus.DRAFT, clock.instant());
        }
        app.moveTo(ApplicationStatus.EDITING, clock.instant());
        return applications.save(app);
    }

    /** The application waiting for the owner's edit, if any (the next plain message is that edit). */
    public Optional<ApplicationEntity> editing() {
        return applications.findByStatusInOrderByIdDesc(List.of(ApplicationStatus.EDITING), PageRequest.of(0, 1)).stream().findFirst();
    }

    /**
     * Apply the owner's reply to an application in editing state: {@code bid 650 5} changes the bid; a short text is an
     * instruction for the AI ("shorter, mention Kafka") when AI is enabled; anything else is the new proposal text.
     */
    public synchronized ApplicationEntity applyEdit(long id, String reply) {
        ApplicationEntity app = require(id, ApplicationStatus.EDITING);
        String text = reply.strip();
        Matcher bid = BID_COMMAND.matcher(text);
        if (bid.matches()) {
            if (app.getBidAmount() == null && senders.byPlatform(app.getPlatform()).map(ProposalSender::needsBid).orElse(false) == false) {
                throw new ApplyException("This route has no bid amount to change.");
            }
            BigDecimal amount = new BigDecimal(bid.group(1).replace(',', '.'));
            Integer days = bid.group(2) == null ? app.getBidDays() : Integer.valueOf(bid.group(2));
            app.changeBid(amount, days, clock.instant());
        } else if (proposals.aiEnabled() && text.length() < INSTRUCTION_MAX_CHARS) {
            Outcome outcome = proposals.revise(jobOf(app).toJob(), app.getText(), text);
            app.changeText(textOf(outcome), clock.instant());
        } else if (text.length() >= MIN_PROPOSAL_CHARS) {
            app.changeText(text, clock.instant());
        } else {
            throw new ApplyException("That is too short to be a proposal. Send the full text"
                    + (proposals.aiEnabled() ? ", or an instruction such as \"shorter, mention Kafka\"" : "")
                    + ", or \"bid 650 5\" to change the bid.");
        }
        app.moveTo(ApplicationStatus.DRAFT, clock.instant());
        return applications.save(app);
    }

    public synchronized ApplicationEntity cancel(long id) {
        ApplicationEntity app = require(id, ApplicationStatus.DRAFT, ApplicationStatus.EDITING, ApplicationStatus.CONFIRMING, ApplicationStatus.FAILED);
        app.moveTo(ApplicationStatus.CANCELLED, clock.instant());
        return applications.save(app);
    }

    /** The owner answered "no" to the final confirmation: back to a draft, nothing sent. */
    public synchronized ApplicationEntity declineConfirmation(long id) {
        ApplicationEntity app = require(id, ApplicationStatus.CONFIRMING);
        app.moveTo(ApplicationStatus.DRAFT, clock.instant());
        return applications.save(app);
    }

    /** No automated submission: the owner copies the text and opens the link. */
    public synchronized ApplicationEntity copyAndOpen(long id) {
        ApplicationEntity app = require(id, ApplicationStatus.DRAFT, ApplicationStatus.FAILED);
        app.moveTo(ApplicationStatus.COPIED, clock.instant());
        return applications.save(app);
    }

    // ---- sending (only on the owner's explicit approval) --------------------------------------------------------

    /** The owner approved this application (Send button or an "ok" reply). Asks for a final confirmation when the action is limited. */
    public synchronized SendDecision requestSend(long id) {
        ApplicationEntity app = require(id, ApplicationStatus.DRAFT, ApplicationStatus.FAILED);
        ProposalSender sender = senderOf(app);
        Job job = jobOf(app).toJob();
        Optional<String> block = blockReason(app, sender);
        if (block.isPresent()) {
            return new SendDecision.Blocked(app, block.get());
        }
        Optional<String> confirmation = sender.confirmation(job);
        if (confirmation.isPresent()) {
            app.moveTo(ApplicationStatus.CONFIRMING, clock.instant());
            return new SendDecision.NeedsConfirmation(applications.save(app), confirmation.get());
        }
        return execute(app, sender, job);
    }

    /** The owner answered "yes" to the final confirmation. */
    public synchronized SendDecision confirmSend(long id) {
        ApplicationEntity app = require(id, ApplicationStatus.CONFIRMING);
        ProposalSender sender = senderOf(app);
        Optional<String> block = blockReason(app, sender);
        if (block.isPresent()) {
            app.moveTo(ApplicationStatus.DRAFT, clock.instant());
            return new SendDecision.Blocked(applications.save(app), block.get());
        }
        return execute(app, sender, jobOf(app).toJob());
    }

    private SendDecision execute(ApplicationEntity app, ProposalSender sender, Job job) {
        Bid bid = app.getBidAmount() == null ? null : new Bid(app.getBidAmount(), app.getBidCurrency(), app.getBidDays() == null ? config.defaultDays() : app.getBidDays(), null);
        SendResult result;
        try {
            result = sender.send(new SendRequest(job, app.getText(), bid, app.getRecipient()));
        } catch (RuntimeException e) {
            log.error("event=application_error platform={}", sender.platform(), e);
            result = SendResult.failed("Unexpected error while sending; nothing was confirmed as sent.");
        }
        Instant now = clock.instant();
        if (result.ok()) {
            try {
                app.markSent(result.responseId(), now);
                return new SendDecision.Sent(applications.save(app));
            } catch (DataIntegrityViolationException e) {
                // The platform accepted it, but the database says this job was already sent: record that loudly.
                log.error("event=duplicate_send_detected job_id={}", app.getJobId());
                return new SendDecision.Blocked(app, "This job was already sent before (duplicate protection).");
            }
        }
        app.markFailed(result.message(), now);
        log.warn("event=application_failed platform={} reason={}", sender.platform(), result.message());
        return new SendDecision.Failed(applications.save(app), result.message());
    }

    private Optional<String> blockReason(ApplicationEntity app, ProposalSender sender) {
        if (applications.existsByJobIdAndStatus(app.getJobId(), ApplicationStatus.SENT)) {
            return Optional.of("An application for this job was already sent. The bot never sends the same job twice.");
        }
        Instant now = clock.instant();
        Instant dayStart = dayStart(now);
        long today = applications.countByStatusAndSentAtGreaterThanEqual(ApplicationStatus.SENT, dayStart);
        if (today >= config.dailyLimit()) {
            return Optional.of("Daily limit reached: %d of %d applications sent today. Try again tomorrow or use Copy & open.".formatted(today, config.dailyLimit()));
        }
        Integer platformLimit = config.platformLimits().get(sender.platform());
        if (platformLimit != null) {
            long onPlatform = applications.countByStatusAndPlatformAndSentAtGreaterThanEqual(ApplicationStatus.SENT, sender.platform(), dayStart);
            if (onPlatform >= platformLimit) {
                return Optional.of("Daily limit for %s reached (%d of %d).".formatted(sender.platform(), onPlatform, platformLimit));
            }
        }
        Optional<Instant> next = nextAllowed(now);
        if (next.isPresent()) {
            return Optional.of("Please wait %d s between sends (the bot never sends in bursts).".formatted(Math.max(1, Duration.between(now, next.get()).toSeconds())));
        }
        return Optional.empty();
    }

    private Optional<Instant> nextAllowed(Instant now) {
        return applications.findFirstByStatusOrderBySentAtDesc(ApplicationStatus.SENT)
                .map(last -> last.getSentAt().plus(config.minGap()))
                .filter(next -> next.isAfter(now));
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    public Optional<ApplicationEntity> findById(long id) {
        return applications.findById(id);
    }

    public Optional<ApplicationEntity> findByDraftMessage(long messageId) {
        return applications.findFirstByDraftMessageIdOrderByIdDesc(messageId);
    }

    public synchronized void attachDraftMessage(long id, long messageId) {
        applications.findById(id).ifPresent(app -> {
            app.setDraftMessageId(messageId);
            applications.save(app);
        });
    }

    public List<ApplicationEntity> recent(int limit) {
        return applications.findAllByOrderByIdDesc(PageRequest.of(0, limit));
    }

    public Optional<ProposalSender> routeOf(ApplicationEntity app) {
        return senders.byPlatform(app.getPlatform());
    }

    public Optional<String> missingSetupFor(JobEntity job) {
        return senders.missingSetupFor(job.toJob());
    }

    public JobEntity jobOf(ApplicationEntity app) {
        return jobs.findById(app.getJobId()).orElseThrow(() -> new ApplyException("The job of this application no longer exists."));
    }

    public LimitsView limits() {
        Instant now = clock.instant();
        Instant dayStart = dayStart(now);
        Map<String, Long> perPlatform = new HashMap<>();
        applications.sentPerPlatformSince(dayStart).forEach(row -> perPlatform.put(row.getPlatform(), row.getSent()));
        return new LimitsView(applications.countByStatusAndSentAtGreaterThanEqual(ApplicationStatus.SENT, dayStart), config.dailyLimit(), perPlatform,
                config.platformLimits(), config.minGap(), nextAllowed(now));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private Instant dayStart(Instant now) {
        return LocalDate.ofInstant(now, config.zone()).atStartOfDay(config.zone()).toInstant();
    }

    private ProposalSender senderOf(ApplicationEntity app) {
        return senders.byPlatform(app.getPlatform()).orElseThrow(() -> new ApplyException(
                "There is no official way to send this application automatically. Use Copy & open and submit it yourself."));
    }

    private ApplicationEntity require(long id, ApplicationStatus... allowed) {
        ApplicationEntity app = applications.findById(id).orElseThrow(() -> new ApplyException("That application no longer exists."));
        if (!List.of(allowed).contains(app.getStatus())) {
            throw new ApplyException("This application is already %s, so that action is not available.".formatted(app.getStatus().name().toLowerCase()));
        }
        return app;
    }

    private String generate(Job job) {
        return textOf(proposals.draft(job));
    }

    private static String textOf(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Drafted drafted -> drafted.text();
            case Outcome.Disabled ignored -> throw new ApplyException(
                    "AI is disabled (no ANTHROPIC_API_KEY), so I cannot draft a proposal. Set the key and restart; everything else keeps working.");
            case Outcome.Failed failed -> throw new ApplyException(failed.message());
        };
    }
}
