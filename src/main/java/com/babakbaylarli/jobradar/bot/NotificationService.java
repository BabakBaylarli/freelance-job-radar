package com.babakbaylarli.jobradar.bot;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.config.QuietHoursSettings;
import com.babakbaylarli.jobradar.persistence.Delivery;
import com.babakbaylarli.jobradar.persistence.JobEntity;
import com.babakbaylarli.jobradar.persistence.JobRepository;
import com.babakbaylarli.jobradar.persistence.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Decides when matching jobs reach the owner, so the bot never spams:
 * <ul>
 *   <li>paused: nothing is sent, jobs wait;</li>
 *   <li>quiet hours: jobs are deferred and go out as <b>one digest</b> once the window ends;</li>
 *   <li>otherwise best-scoring jobs are sent as individual messages, at most {@code max-per-hour};
 *       the overflow is deferred into the next digest.</li>
 * </ul>
 * A digest (top {@code digest-size} deferred jobs) is sent when something is deferred, it is not quiet time, and at
 * least {@code digest-min-interval} passed since the last one. {@code /digest} sends one on demand.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final int PENDING_BATCH = 200;
    private static final List<Delivery> WAITING = List.of(Delivery.PENDING, Delivery.DEFERRED);

    private final TelegramGateway gateway;
    private final JobRepository jobs;
    private final BotStateService state;
    private final MessageFormatter formatter;
    private final JobRadarProperties.Notifications settings;
    private final Long ownerChatId;
    private final Clock clock;

    public NotificationService(TelegramGateway gateway, JobRepository jobs, BotStateService state,
            MessageFormatter formatter, JobRadarProperties properties, Clock clock) {
        this.gateway = gateway;
        this.jobs = jobs;
        this.state = state;
        this.formatter = formatter;
        this.settings = properties.notifications();
        this.ownerChatId = properties.telegram().ownerChatId();
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${jobradar.flush-initial-delay:PT20S}", fixedDelayString = "${jobradar.flush-interval:PT1M}")
    public void scheduledFlush() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.error("event=flush_failed", e);
        }
    }

    public synchronized void flush() {
        if (!gateway.enabled() || ownerChatId == null || state.isPaused()) {
            return;
        }
        Instant now = clock.instant();
        QuietHoursSettings quiet = settings.quietHours();
        List<JobEntity> pending = jobs.findByDeliveryInAndUserStatusOrderByScoreDescIdDesc(
                List.of(Delivery.PENDING), UserStatus.NONE, PageRequest.of(0, PENDING_BATCH));

        if (quiet.isQuiet(now)) {
            pending.forEach(job -> {
                job.defer();
                jobs.save(job);
            });
            if (!pending.isEmpty()) {
                log.info("event=deferred reason=quiet_hours count={}", pending.size());
            }
            return;
        }

        long sentLastHour = jobs.countByDeliveryAndNotifiedAtAfter(Delivery.SENT_LIVE, now.minus(Duration.ofHours(1)));
        long budget = Math.max(0, settings.maxPerHour() - sentLastHour);
        int sent = 0;
        int deferred = 0;
        for (JobEntity job : pending) {
            if (budget <= 0) {
                job.defer(); // over the hourly limit: goes into the next digest
                jobs.save(job);
                deferred++;
                continue;
            }
            try {
                gateway.sendHtml(ownerChatId, formatter.jobCard(job), formatter.jobKeyboard(job, false));
            } catch (TelegramSendException e) {
                log.warn("event=send_failed error={} - will retry on the next flush", e.getMessage());
                break;
            }
            job.markSent(Delivery.SENT_LIVE, now);
            jobs.save(job);
            budget--;
            sent++;
        }
        if (sent > 0 || deferred > 0) {
            log.info("event=flush sent={} deferred_over_limit={}", sent, deferred);
        }
        maybeSendDigest(now);
    }

    private void maybeSendDigest(Instant now) {
        if (jobs.countByDeliveryAndUserStatus(Delivery.DEFERRED, UserStatus.NONE) == 0) {
            return;
        }
        boolean due = state.lastDigestAt()
                .map(last -> !now.isBefore(last.plus(settings.digestMinInterval())))
                .orElse(true);
        if (due) {
            sendDigest(List.of(Delivery.DEFERRED), "Digest", now);
        }
    }

    /** {@code /digest}: send the top unseen matches now, regardless of quiet hours or the pause flag. */
    public synchronized int sendDigestNow() {
        if (!gateway.enabled() || ownerChatId == null) {
            return 0;
        }
        return sendDigest(WAITING, "Top unseen matches", clock.instant());
    }

    private int sendDigest(List<Delivery> from, String title, Instant now) {
        List<JobEntity> top = jobs.findByDeliveryInAndUserStatusOrderByScoreDescIdDesc(
                from, UserStatus.NONE, PageRequest.of(0, settings.digestSize()));
        if (top.isEmpty()) {
            return 0;
        }
        long totalWaiting = jobs.countByDeliveryInAndUserStatus(from, UserStatus.NONE);
        MessageFormatter.DigestMessage message = formatter.digest(top, totalWaiting - top.size(), title + " — " + top.size() + " match" + (top.size() == 1 ? "" : "es"));
        try {
            gateway.sendHtml(ownerChatId, message.html(), message.keyboard());
        } catch (TelegramSendException e) {
            log.warn("event=digest_failed error={}", e.getMessage());
            return 0;
        }
        message.included().forEach(job -> {
            job.markSent(Delivery.SENT_DIGEST, now);
            jobs.save(job);
        });
        state.recordDigest(now);
        log.info("event=digest_sent count={} remaining={}", message.included().size(), totalWaiting - message.included().size());
        return message.included().size();
    }
}
