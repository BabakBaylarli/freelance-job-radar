package com.babakbaylarli.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.babakbaylarli.jobradar.ai.ProposalService;
import com.babakbaylarli.jobradar.ai.ProposalService.Outcome;
import com.babakbaylarli.jobradar.apply.ApplicationService.SendDecision;
import com.babakbaylarli.jobradar.persistence.ApplicationEntity;
import com.babakbaylarli.jobradar.persistence.ApplicationRepository;
import com.babakbaylarli.jobradar.persistence.ApplicationStatus;
import com.babakbaylarli.jobradar.persistence.Delivery;
import com.babakbaylarli.jobradar.persistence.JobEntity;
import com.babakbaylarli.jobradar.persistence.JobRepository;
import com.babakbaylarli.jobradar.source.Budget;
import com.babakbaylarli.jobradar.source.Job;
import com.babakbaylarli.jobradar.source.JobType;
import com.babakbaylarli.jobradar.source.Market;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The approval flow, its limits and its "never twice" guarantee, against real Postgres and fake senders. */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ApplicationService.class, SenderRegistry.class, BidAdvisor.class, ApplicationServiceTest.Config.class})
@TestPropertySource(properties = {
        "jobradar.apply.daily-limit=3",
        "jobradar.apply.platform-limits.email=1",
        "jobradar.apply.min-gap=PT2M",
        "jobradar.apply.min-fixed-bid.USD=100",
        "jobradar.apply.default-days=7",
        "jobradar.apply.zone=UTC"})
class ApplicationServiceTest {

    static final Instant NOON = Instant.parse("2026-11-27T12:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static class MutableClock extends Clock {
        Instant now = NOON;

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** A sender whose behaviour the test controls; counts every call so "nothing was sent" can be asserted. */
    static class FakeSender implements ProposalSender {
        final String platform;
        final String sourcePrefix;
        final String confirmation;
        final boolean needsBid;
        final List<SendRequest> calls = new ArrayList<>();
        SendResult next = SendResult.ok("ref-1");
        boolean explode;

        FakeSender(String platform, String sourcePrefix, String confirmation, boolean needsBid) {
            this.platform = platform;
            this.sourcePrefix = sourcePrefix;
            this.confirmation = confirmation;
            this.needsBid = needsBid;
        }

        @Override public String platform() { return platform; }
        @Override public String route() { return platform + " route"; }
        @Override public boolean supports(Job job) { return job.source().startsWith(sourcePrefix); }
        @Override public Optional<String> unavailableReason() { return Optional.empty(); }
        @Override public Optional<String> recipient(Job job) { return needsBid ? Optional.empty() : Optional.of("hr@acme.example"); }
        @Override public Optional<String> confirmation(Job job) { return Optional.ofNullable(confirmation); }
        @Override public boolean needsBid() { return needsBid; }

        @Override
        public SendResult send(SendRequest request) {
            calls.add(request);
            if (explode) {
                throw new IllegalStateException("sender bug");
            }
            return next;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean FakeSender freelancerSender() { return new FakeSender("freelancer", "freelancer", "This uses 1 of your bids.", true); }
        @Bean FakeSender emailSender() { return new FakeSender("email", "dou", null, false); }
    }

    @MockitoBean ProposalService proposals;

    @Autowired ApplicationService service;
    @Autowired ApplicationRepository applications;
    @Autowired JobRepository jobs;
    @Autowired MutableClock clock;
    @Autowired FakeSender freelancerSender;
    @Autowired FakeSender emailSender;

    @BeforeEach
    void setUp() {
        clock.now = NOON;
        freelancerSender.calls.clear();
        emailSender.calls.clear();
        freelancerSender.next = SendResult.ok("ref-1");
        emailSender.next = SendResult.ok("<msg-1@mail>");
        freelancerSender.explode = false;
        when(proposals.draft(any(Job.class))).thenReturn(new Outcome.Drafted("Hi, I can build this payments service. Which Kafka version do you run?"));
        when(proposals.aiEnabled()).thenReturn(true);
    }

    private JobEntity job(String source, String externalId, Budget budget, String description) {
        Job job = new Job(source, externalId, "Job " + externalId, "Acme", "https://x.test/" + source + "/" + externalId, List.of("java"), description, null,
                NOON, Market.INTERNATIONAL, JobType.FREELANCE_PROJECT, budget);
        return jobs.save(new JobEntity(job, job.url(), NOON, 9, List.of("java"), null, true, Delivery.SENT_LIVE, null));
    }

    private JobEntity freelancerJob(String id) {
        return job("freelancer", id, Budget.of(500, 1500, "USD", false), "desc");
    }

    private JobEntity douJob(String id) {
        return job("dou", id, null, "Send CV to hr@acme.example");
    }

    // ---- drafting -----------------------------------------------------------------------------------------------

    @Test
    void draftingNeverSendsAndFillsInTheRouteBidAndRecipient() {
        ApplicationEntity api = service.startDraft(freelancerJob("101"));
        ApplicationEntity mail = service.startDraft(douJob("102"));

        assertThat(api.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(api.getPlatform()).isEqualTo("freelancer");
        assertThat(api.getBidAmount()).isEqualByComparingTo("1000"); // middle of 500..1500
        assertThat(api.getBidCurrency()).isEqualTo("USD");
        assertThat(api.getBidDays()).isEqualTo(7);
        assertThat(mail.getPlatform()).isEqualTo("email");
        assertThat(mail.getRecipient()).isEqualTo("hr@acme.example");
        assertThat(mail.getBidAmount()).isNull();
        assertThat(freelancerSender.calls).isEmpty();
        assertThat(emailSender.calls).isEmpty();
    }

    @Test
    void aJobWithoutAnOfficialRouteGetsACopyDraft() {
        ApplicationEntity copy = service.startDraft(job("remoteok", "103", null, "no contact"));

        assertThat(copy.getPlatform()).isEqualTo("copy");
        assertThatThrownBy(() -> service.requestSend(copy.getId())).isInstanceOf(ApplyException.class).hasMessageContaining("Copy & open");
        assertThat(service.copyAndOpen(copy.getId()).getStatus()).isEqualTo(ApplicationStatus.COPIED);
    }

    @Test
    void pressingDraftTwiceReturnsTheSameOpenDraft() {
        JobEntity job = freelancerJob("104");

        assertThat(service.startDraft(job).getId()).isEqualTo(service.startDraft(job).getId());
    }

    @Test
    void draftingNeedsAI() {
        when(proposals.draft(any(Job.class))).thenReturn(new Outcome.Disabled());

        assertThatThrownBy(() -> service.startDraft(freelancerJob("105"))).isInstanceOf(ApplyException.class).hasMessageContaining("ANTHROPIC_API_KEY");
    }

    // ---- approval, confirmation, sending ------------------------------------------------------------------------

    @Test
    void limitedActionsAskForConfirmationFirstAndSendOnlyAfterYes() {
        ApplicationEntity app = service.startDraft(freelancerJob("201"));

        SendDecision decision = service.requestSend(app.getId());

        assertThat(decision).isInstanceOf(SendDecision.NeedsConfirmation.class);
        assertThat(((SendDecision.NeedsConfirmation) decision).note()).contains("1 of your bids");
        assertThat(freelancerSender.calls).isEmpty(); // asking is not sending
        assertThat(applications.findById(app.getId()).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.CONFIRMING);

        SendDecision sent = service.confirmSend(app.getId());

        assertThat(sent).isInstanceOf(SendDecision.Sent.class);
        assertThat(freelancerSender.calls).hasSize(1);
        SendRequest request = freelancerSender.calls.get(0);
        assertThat(request.bid().amount()).isEqualByComparingTo("1000");
        assertThat(request.text()).contains("payments service");
        ApplicationEntity stored = applications.findById(app.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ApplicationStatus.SENT);
        assertThat(stored.getResponseId()).isEqualTo("ref-1");
        assertThat(stored.getSentAt()).isEqualTo(NOON);
    }

    @Test
    void sayingNoToTheConfirmationSendsNothingAndKeepsTheDraft() {
        ApplicationEntity app = service.startDraft(freelancerJob("202"));
        service.requestSend(app.getId());

        ApplicationEntity declined = service.declineConfirmation(app.getId());

        assertThat(declined.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(freelancerSender.calls).isEmpty();
        assertThatThrownBy(() -> service.confirmSend(app.getId())).isInstanceOf(ApplyException.class); // no longer waiting for a yes
    }

    @Test
    void routesWithoutAConfirmationSendRightAfterTheOwnersApproval() {
        ApplicationEntity app = service.startDraft(douJob("203"));

        SendDecision decision = service.requestSend(app.getId());

        assertThat(decision).isInstanceOf(SendDecision.Sent.class);
        assertThat(emailSender.calls).singleElement().satisfies(r -> assertThat(r.recipient()).isEqualTo("hr@acme.example"));
    }

    @Test
    void aFailedSendIsRecordedShownAndNeverRetriedByItself() {
        freelancerSender.next = SendResult.failed("Freelancer.com refused the bid: no bids left");
        ApplicationEntity app = service.startDraft(freelancerJob("301"));
        service.requestSend(app.getId());

        SendDecision decision = service.confirmSend(app.getId());

        assertThat(decision).isInstanceOf(SendDecision.Failed.class);
        ApplicationEntity failed = applications.findById(app.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ApplicationStatus.FAILED);
        assertThat(failed.getError()).contains("no bids left");
        assertThat(freelancerSender.calls).hasSize(1); // one attempt, no automatic retry
        assertThat(applications.existsByJobIdAndStatus(failed.getJobId(), ApplicationStatus.SENT)).isFalse();
    }

    @Test
    void afterAFailureTheOwnerCanRetryOrCopyAndOpen() {
        freelancerSender.next = SendResult.failed("temporary");
        ApplicationEntity retry = service.startDraft(freelancerJob("302"));
        service.requestSend(retry.getId());
        service.confirmSend(retry.getId());
        freelancerSender.next = SendResult.ok("ref-2");
        clock.now = clock.now.plus(Duration.ofMinutes(5));

        service.requestSend(retry.getId()); // Retry button -> asks for confirmation again
        SendDecision decision = service.confirmSend(retry.getId());

        assertThat(decision).isInstanceOf(SendDecision.Sent.class);

        freelancerSender.next = SendResult.failed("temporary");
        ApplicationEntity copy = service.startDraft(freelancerJob("303"));
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        service.requestSend(copy.getId());
        service.confirmSend(copy.getId());
        assertThat(service.copyAndOpen(copy.getId()).getStatus()).isEqualTo(ApplicationStatus.COPIED);
    }

    @Test
    void anExceptionInsideASenderBecomesAFailureNotACrash() {
        freelancerSender.explode = true;
        ApplicationEntity app = service.startDraft(freelancerJob("304"));
        service.requestSend(app.getId());

        SendDecision decision = service.confirmSend(app.getId());

        assertThat(decision).isInstanceOf(SendDecision.Failed.class);
        assertThat(applications.findById(app.getId()).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.FAILED);
    }

    // ---- safety rails -------------------------------------------------------------------------------------------

    @Test
    void theSameJobIsNeverSentTwice() {
        JobEntity job = douJob("401");
        service.requestSend(service.startDraft(job).getId());
        clock.now = clock.now.plus(Duration.ofMinutes(5));

        assertThatThrownBy(() -> service.startDraft(job)).isInstanceOf(ApplyException.class).hasMessageContaining("already sent");

        // even a second application created behind the service's back cannot be sent
        ApplicationEntity sneaky = applications.save(new ApplicationEntity(job.getId(), "email", "again", null, null, null, "hr@acme.example", clock.instant()));
        SendDecision decision = service.requestSend(sneaky.getId());
        assertThat(decision).isInstanceOf(SendDecision.Blocked.class);
        assertThat(((SendDecision.Blocked) decision).reason()).contains("never sends the same job twice");
        assertThat(emailSender.calls).hasSize(1);
    }

    @Test
    void theDailyLimitStopsSendingAndNextDayResetsIt() {
        for (int i = 1; i <= 3; i++) { // daily limit is 3; platform limit for e-mail is 1, so use the API route
            ApplicationEntity app = service.startDraft(freelancerJob("50" + i));
            service.requestSend(app.getId());
            assertThat(service.confirmSend(app.getId())).isInstanceOf(SendDecision.Sent.class);
            clock.now = clock.now.plus(Duration.ofMinutes(3));
        }
        ApplicationEntity fourth = service.startDraft(freelancerJob("504"));

        SendDecision blocked = service.requestSend(fourth.getId());

        assertThat(blocked).isInstanceOf(SendDecision.Blocked.class);
        assertThat(((SendDecision.Blocked) blocked).reason()).contains("Daily limit reached: 3 of 3");
        assertThat(freelancerSender.calls).hasSize(3);
        assertThat(service.limits().sentToday()).isEqualTo(3);

        clock.now = Instant.parse("2026-11-28T09:00:00Z"); // the next day

        assertThat(service.requestSend(fourth.getId())).isInstanceOf(SendDecision.NeedsConfirmation.class);
    }

    @Test
    void aPlatformLimitCapsOnePlatformWithoutBlockingTheOthers() {
        service.requestSend(service.startDraft(douJob("601")).getId());        // e-mail: 1 of 1
        clock.now = clock.now.plus(Duration.ofMinutes(3));
        ApplicationEntity secondMail = service.startDraft(douJob("602"));

        SendDecision blocked = service.requestSend(secondMail.getId());

        assertThat(blocked).isInstanceOf(SendDecision.Blocked.class);
        assertThat(((SendDecision.Blocked) blocked).reason()).contains("Daily limit for email reached (1 of 1)");
        ApplicationEntity api = service.startDraft(freelancerJob("603"));
        assertThat(service.requestSend(api.getId())).isInstanceOf(SendDecision.NeedsConfirmation.class); // the API route still works
    }

    @Test
    void thereIsAMinimumGapBetweenSends() {
        service.requestSend(service.startDraft(freelancerJob("701")).getId());
        service.confirmSend(applications.findAll().get(0).getId());
        clock.now = clock.now.plusSeconds(30);
        ApplicationEntity next = service.startDraft(freelancerJob("702"));

        SendDecision blocked = service.requestSend(next.getId());

        assertThat(blocked).isInstanceOf(SendDecision.Blocked.class);
        assertThat(((SendDecision.Blocked) blocked).reason()).contains("wait 90 s");
        assertThat(service.limits().nextAllowed()).contains(NOON.plus(Duration.ofMinutes(2)));
    }

    @Test
    void copiedApplicationsDoNotUseUpTheDailyLimit() {
        for (int i = 1; i <= 5; i++) {
            ApplicationEntity copy = service.startDraft(job("remoteok", "80" + i, null, "x"));
            service.copyAndOpen(copy.getId());
        }

        assertThat(service.limits().sentToday()).isZero();
    }

    // ---- editing ------------------------------------------------------------------------------------------------

    @Test
    void editingWithTheFullNewTextReplacesTheDraft() {
        ApplicationEntity app = service.startDraft(freelancerJob("901"));
        service.beginEdit(app.getId());
        when(proposals.aiEnabled()).thenReturn(false); // no AI: any reply is the new text

        ApplicationEntity edited = service.applyEdit(app.getId(), "My own wording of the proposal, written by hand, ending with a question?");

        assertThat(edited.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(edited.getText()).startsWith("My own wording");
        assertThat(freelancerSender.calls).isEmpty(); // editing never sends
    }

    @Test
    void aShortReplyIsAnInstructionForTheAiWhenItIsEnabled() {
        ApplicationEntity app = service.startDraft(freelancerJob("902"));
        service.beginEdit(app.getId());
        when(proposals.revise(any(Job.class), anyString(), anyString())).thenReturn(new Outcome.Drafted("Shorter, with Kafka. Which version do you run?"));

        ApplicationEntity edited = service.applyEdit(app.getId(), "shorter, mention Kafka");

        assertThat(edited.getText()).isEqualTo("Shorter, with Kafka. Which version do you run?");
        assertThat(edited.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
    }

    @Test
    void theBidCanBeChangedWithABidReply() {
        ApplicationEntity app = service.startDraft(freelancerJob("903"));
        service.beginEdit(app.getId());

        ApplicationEntity edited = service.applyEdit(app.getId(), "bid 650 5");

        assertThat(edited.getBidAmount()).isEqualByComparingTo("650");
        assertThat(edited.getBidDays()).isEqualTo(5);
        service.beginEdit(app.getId());
        assertThat(service.applyEdit(app.getId(), "bid 700.50").getBidAmount()).isEqualByComparingTo(new BigDecimal("700.50"));
        // once the edit is applied the draft is back to DRAFT: a further reply is NOT an edit any more
        assertThatThrownBy(() -> service.applyEdit(app.getId(), "bid 1 1")).isInstanceOf(ApplyException.class).hasMessageContaining("already draft");
    }

    @Test
    void aTooShortReplyIsRejectedAndNothingChanges() {
        ApplicationEntity app = service.startDraft(freelancerJob("904"));
        service.beginEdit(app.getId());
        when(proposals.aiEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.applyEdit(app.getId(), "ok then")).isInstanceOf(ApplyException.class).hasMessageContaining("too short");
        assertThat(applications.findById(app.getId()).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.EDITING);
    }

    @Test
    void onlyOneDraftIsInEditModeAtATime() {
        ApplicationEntity first = service.startDraft(freelancerJob("905"));
        ApplicationEntity second = service.startDraft(douJob("906"));
        service.beginEdit(first.getId());

        service.beginEdit(second.getId());

        assertThat(applications.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(service.editing()).get().extracting(ApplicationEntity::getId).isEqualTo(second.getId());
    }

    @Test
    void regenerateReplacesTheTextCancelEndsTheApplication() {
        ApplicationEntity app = service.startDraft(douJob("907"));
        when(proposals.draft(any(Job.class))).thenReturn(new Outcome.Drafted("A completely new angle on your problem. What is your deadline?"));

        assertThat(service.regenerate(app.getId()).getText()).startsWith("A completely new angle");
        assertThat(service.cancel(app.getId()).getStatus()).isEqualTo(ApplicationStatus.CANCELLED);
        assertThatThrownBy(() -> service.requestSend(app.getId())).isInstanceOf(ApplyException.class).hasMessageContaining("cancelled");
    }

    @Test
    void sentApplicationsCannotBeChangedAnyMore() {
        ApplicationEntity app = service.startDraft(douJob("908"));
        service.requestSend(app.getId());

        assertThatThrownBy(() -> service.cancel(app.getId())).isInstanceOf(ApplyException.class).hasMessageContaining("sent");
        assertThatThrownBy(() -> service.regenerate(app.getId())).isInstanceOf(ApplyException.class);
        assertThatThrownBy(() -> service.beginEdit(app.getId())).isInstanceOf(ApplyException.class);
    }

    @Test
    void aReplyToTheDraftMessageFindsTheApplication() {
        ApplicationEntity app = service.startDraft(douJob("909"));
        service.attachDraftMessage(app.getId(), 424242L);

        assertThat(service.findByDraftMessage(424242L)).get().extracting(ApplicationEntity::getId).isEqualTo(app.getId());
        assertThat(service.findByDraftMessage(1L)).isEmpty();
        assertThat(service.recent(5)).isNotEmpty();
    }
}
