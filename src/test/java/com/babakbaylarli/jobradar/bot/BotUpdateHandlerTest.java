package com.babakbaylarli.jobradar.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.babakbaylarli.jobradar.TestSupport;
import com.babakbaylarli.jobradar.ai.ProposalService;
import com.babakbaylarli.jobradar.ai.ProposalService.Outcome;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.persistence.Delivery;
import com.babakbaylarli.jobradar.persistence.JobEntity;
import com.babakbaylarli.jobradar.persistence.JobRepository;
import com.babakbaylarli.jobradar.persistence.SourceStateRepository;
import com.babakbaylarli.jobradar.persistence.UserStatus;
import com.babakbaylarli.jobradar.source.Job;
import com.babakbaylarli.jobradar.source.JobSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class BotUpdateHandlerTest {

    private static final long OWNER = 1001L;
    private static final long STRANGER = 2002L;

    private final RecordingGateway gateway = new RecordingGateway();
    private final JobRepository jobs = mock(JobRepository.class);
    private final SourceStateRepository sourceStates = mock(SourceStateRepository.class);
    private final BotStateService state = mock(BotStateService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ProposalService proposals = mock(ProposalService.class);
    private final com.babakbaylarli.jobradar.apply.ApplicationService applications = mock(com.babakbaylarli.jobradar.apply.ApplicationService.class);
    private final JobSource remotive = mock(JobSource.class);

    private BotUpdateHandler handler;

    @BeforeEach
    void setUp() {
        when(remotive.id()).thenReturn("remotive");
        when(remotive.displayName()).thenReturn("Remotive");
        handler = handlerFor(OWNER);
    }

    private BotUpdateHandler handlerFor(Long owner) {
        JobRadarProperties base = TestSupport.props("http://unused");
        JobRadarProperties props = new JobRadarProperties(new JobRadarProperties.Telegram("token", owner), base.ai(), base.matching(),
                base.notifications(), base.http(), base.sources(), base.profilePath(), base.apply());
        return new BotUpdateHandler(gateway, props, jobs, sourceStates, state, notifications, proposals, applications, new MessageFormatter(),
                List.of(remotive), new com.babakbaylarli.jobradar.source.SourceCatalog(List.of(remotive)), Clock.fixed(Instant.parse("2026-11-27T10:00:00Z"), ZoneOffset.UTC));
    }

    private JobEntity storedJob(long id, Delivery delivery) {
        JobEntity job = MessageFormatterTest.job(id, "Java Engineer", "Acme", "remotive", 6, List.of("java"), null, "desc");
        job.markSent(delivery, Instant.parse("2026-11-27T09:00:00Z"));
        when(jobs.findById(id)).thenReturn(Optional.of(job));
        return job;
    }

    // ---- access control ---------------------------------------------------------------------------------------

    @Test
    void strangersGetAPoliteRefusalAndNothingElse() {
        handler.onMessage(STRANGER, "/digest");
        handler.onMessage(STRANGER, "/pause");

        assertThat(gateway.sent).hasSize(2).allSatisfy(s -> assertThat(s.html()).isEqualTo(BotUpdateHandler.PRIVATE_BOT));
        verify(state, never()).setPaused(true);
        verify(notifications, never()).sendDigestNow();
    }

    @Test
    void strangersCannotPressButtons() {
        storedJob(5, Delivery.SENT_LIVE);

        handler.onCallback(STRANGER, 10, "cb1", "h:5");

        assertThat(gateway.answers).containsExactly("Private bot");
        verify(jobs, never()).save(any());
    }

    @Test
    void withoutAnOwnerConfiguredTheBotOnlyRevealsTheCallersOwnChatId() {
        BotUpdateHandler setup = handlerFor(null);

        setup.onMessage(STRANGER, "/keywords");

        assertThat(gateway.sent).hasSize(1);
        assertThat(gateway.sent.get(0).html()).contains("Setup mode").contains("2002").contains("TELEGRAM_OWNER_CHAT_ID");
        verify(state, never()).isPaused();
    }

    // ---- commands ---------------------------------------------------------------------------------------------

    @Test
    void startAndHelpExplainTheBot() {
        when(state.isPaused()).thenReturn(false);
        when(proposals.aiEnabled()).thenReturn(false);

        handler.onMessage(OWNER, "/start");
        handler.onMessage(OWNER, "/help@job_radar_bot");

        assertThat(gateway.texts().get(0)).contains("Freelance Job Radar").contains("AI proposals are disabled");
        assertThat(gateway.texts().get(1)).contains("/digest").contains("/pause");
    }

    @Test
    void pauseAndResumeTogglePersistedState() {
        handler.onMessage(OWNER, "/pause");
        handler.onMessage(OWNER, "/resume");

        verify(state).setPaused(true);
        verify(state).setPaused(false);
        assertThat(gateway.texts()).anySatisfy(t -> assertThat(t).contains("paused"));
        assertThat(gateway.texts()).anySatisfy(t -> assertThat(t).contains("resumed"));
        await().untilAsserted(() -> verify(notifications).scheduledFlush());
    }

    @Test
    void digestSaysSoWhenThereIsNothingNew() {
        when(notifications.sendDigestNow()).thenReturn(0);

        handler.onMessage(OWNER, "/digest");

        assertThat(gateway.texts()).singleElement().satisfies(t -> assertThat(t).contains("No unseen matches"));
    }

    @Test
    void digestDoesNotAddAnExtraReplyWhenItSentOne() {
        when(notifications.sendDigestNow()).thenReturn(3);

        handler.onMessage(OWNER, "/digest");

        assertThat(gateway.sent).isEmpty(); // the digest itself is sent by NotificationService
    }

    @Test
    void savedListsSavedJobs() {
        JobEntity saved = storedJob(3, Delivery.SENT_LIVE);
        when(jobs.findByUserStatusOrderByIdDesc(any(UserStatus.class), any(Pageable.class))).thenReturn(List.of(saved));

        handler.onMessage(OWNER, "/saved");

        assertThat(gateway.texts()).singleElement().satisfies(t -> assertThat(t).contains("Saved jobs").contains("Java Engineer"));
    }

    @Test
    void statsAndKeywordsAreAnswered() {
        when(sourceStates.findAll()).thenReturn(List.of());
        when(jobs.statsSince(any())).thenReturn(List.of());

        handler.onMessage(OWNER, "/stats");
        handler.onMessage(OWNER, "/keywords");

        assertThat(gateway.texts().get(0)).contains("Last 7 days").contains("Remotive");
        assertThat(gateway.texts().get(1)).contains("Matching config");
    }

    @Test
    void unknownInputGetsAHint() {
        handler.onMessage(OWNER, "/nope");
        handler.onMessage(OWNER, "hello");

        assertThat(gateway.texts().get(0)).contains("Unknown command");
        assertThat(gateway.texts().get(1)).contains("/help");
    }

    // ---- buttons ----------------------------------------------------------------------------------------------

    @Test
    void saveMarksTheJobAndUpdatesTheKeyboardOfALiveCard() {
        JobEntity job = storedJob(5, Delivery.SENT_LIVE);

        handler.onCallback(OWNER, 77, "cb", "s:5");

        assertThat(job.getUserStatus()).isEqualTo(UserStatus.SAVED);
        verify(jobs).save(job);
        assertThat(gateway.answers).containsExactly("Saved ⭐ — see /saved");
        assertThat(gateway.edits).singleElement().satisfies(e -> {
            assertThat(e.messageId()).isEqualTo(77);
            assertThat(e.keyboard().rows().get(1).get(0).text()).isEqualTo("✅ Saved");
        });
    }

    @Test
    void hideRemovesTheKeyboardOfALiveCardButKeepsTheDigestKeyboard() {
        JobEntity live = storedJob(5, Delivery.SENT_LIVE);
        JobEntity inDigest = storedJob(6, Delivery.SENT_DIGEST);

        handler.onCallback(OWNER, 1, "a", "h:5");
        handler.onCallback(OWNER, 2, "b", "h:6");

        assertThat(live.getUserStatus()).isEqualTo(UserStatus.HIDDEN);
        assertThat(inDigest.getUserStatus()).isEqualTo(UserStatus.HIDDEN);
        assertThat(gateway.edits).singleElement().satisfies(e -> {
            assertThat(e.messageId()).isEqualTo(1);
            assertThat(e.keyboard().rows()).isEmpty();
        });
    }

    @Test
    void unknownJobOrGarbageCallbackIsHandledGracefully() {
        when(jobs.findById(999L)).thenReturn(Optional.empty());

        handler.onCallback(OWNER, 1, "a", "d:999");
        handler.onCallback(OWNER, 1, "b", "garbage");
        handler.onCallback(OWNER, 1, "c", null);

        assertThat(gateway.answers).containsExactly("That job is no longer available.", "That job is no longer available.", "That job is no longer available.");
    }

    @Test
    void draftExplainsWhenAiIsDisabled() {
        storedJob(5, Delivery.SENT_LIVE);
        when(proposals.aiEnabled()).thenReturn(false);

        handler.onCallback(OWNER, 1, "cb", "d:5");

        assertThat(gateway.answers).containsExactly("AI is disabled");
        assertThat(gateway.texts()).singleElement().satisfies(t -> assertThat(t).contains("AI proposals are disabled").contains("ANTHROPIC_API_KEY"));
        verify(proposals, never()).draft(any());
    }

    private com.babakbaylarli.jobradar.persistence.ApplicationEntity draftOf(JobEntity job, String text) {
        var app = new com.babakbaylarli.jobradar.persistence.ApplicationEntity(job.getId(), "copy", text, null, null, null, null, Instant.parse("2026-11-27T10:00:00Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(app, "id", 41L);
        when(applications.jobOf(app)).thenReturn(job);
        when(applications.routeOf(app)).thenReturn(Optional.empty());
        when(applications.missingSetupFor(job)).thenReturn(Optional.empty());
        return app;
    }

    @Test
    void draftShowsTheApplicationWithButtonsAndRemembersTheMessageIdForOkReplies() {
        JobEntity job = storedJob(5, Delivery.SENT_LIVE);
        when(proposals.aiEnabled()).thenReturn(true);
        var app = draftOf(job, "Hi Acme, your payments ledger rebuild sounds great. Which Kafka version do you run?");
        when(applications.startDraft(job)).thenReturn(app);

        handler.onCallback(OWNER, 1, "cb", "d:5");

        assertThat(gateway.answers).containsExactly("Drafting… ✍️");
        await().untilAsserted(() -> assertThat(gateway.sent).singleElement().satisfies(sent -> {
            assertThat(sent.html()).contains("<pre>Hi Acme, your payments ledger rebuild").contains("No official way to send");
            assertThat(sent.keyboard().rows().get(0)).extracting(Button::callbackData).containsExactly("ak:41", "ae:41"); // Copy & open, Edit
            verify(applications).attachDraftMessage(41L, sent.messageId());
        }));
    }

    @Test
    void draftProblemsAreShownToTheOwnerNotThrown() {
        storedJob(5, Delivery.SENT_LIVE);
        when(proposals.aiEnabled()).thenReturn(true);
        when(applications.startDraft(any())).thenThrow(new com.babakbaylarli.jobradar.apply.ApplyException("You already sent an application for this job."));

        handler.onCallback(OWNER, 1, "cb", "d:5");

        await().untilAsserted(() -> assertThat(gateway.texts()).singleElement().satisfies(t -> assertThat(t).contains("already sent")));
    }

    @Test
    void unexpectedDraftErrorsAreContained() {
        storedJob(5, Delivery.SENT_LIVE);
        when(proposals.aiEnabled()).thenReturn(true);
        when(applications.startDraft(any())).thenThrow(new IllegalStateException("boom"));

        handler.onCallback(OWNER, 1, "cb", "d:5");

        await().untilAsserted(() -> assertThat(gateway.texts()).singleElement().satisfies(t -> assertThat(t).contains("try again")));
    }

    // ---- /markets and /sources ----------------------------------------------------------------------------------

    @Test
    void marketsShowsToggleButtonsForEveryMarket() {
        when(state.enabledMarkets()).thenReturn(java.util.EnumSet.of(com.babakbaylarli.jobradar.source.Market.INTERNATIONAL, com.babakbaylarli.jobradar.source.Market.AZ));

        handler.onMessage(OWNER, "/markets");

        assertThat(gateway.sent).hasSize(1);
        var keyboard = gateway.sent.get(0).keyboard();
        assertThat(keyboard.rows()).hasSize(4);
        assertThat(keyboard.rows().get(0).get(0)).isEqualTo(new Button("✅ 🌍 International", "m:INTERNATIONAL"));
        assertThat(keyboard.rows().get(1).get(0)).isEqualTo(new Button("⬜ 🇷🇺 Russia", "m:RU"));
        assertThat(keyboard.rows().get(3).get(0).text()).startsWith("✅ 🇦🇿");
    }

    @Test
    void pressingAMarketButtonTogglesItAndRefreshesTheKeyboard() {
        when(state.toggleMarket(com.babakbaylarli.jobradar.source.Market.RU)).thenReturn(java.util.EnumSet.of(
                com.babakbaylarli.jobradar.source.Market.INTERNATIONAL, com.babakbaylarli.jobradar.source.Market.RU));

        handler.onCallback(OWNER, 55, "cb", "m:RU");

        verify(state).toggleMarket(com.babakbaylarli.jobradar.source.Market.RU);
        assertThat(gateway.answers).containsExactly("🇷🇺 Russia: on");
        assertThat(gateway.edits).singleElement().satisfies(e -> {
            assertThat(e.messageId()).isEqualTo(55);
            assertThat(e.keyboard().rows().get(1).get(0).text()).isEqualTo("✅ 🇷🇺 Russia");
        });
    }

    @Test
    void unknownMarketButtonIsIgnored() {
        handler.onCallback(OWNER, 1, "cb", "m:MARS");

        assertThat(gateway.answers).containsExactly("Unknown market");
        verify(state, never()).toggleMarket(any());
    }

    @Test
    void strangersCannotToggleMarkets() {
        handler.onCallback(STRANGER, 1, "cb", "m:RU");

        assertThat(gateway.answers).containsExactly("Private bot");
        verify(state, never()).toggleMarket(any());
    }

    @Test
    void sourcesListsEveryKnownSourceWithItsState() {
        when(sourceStates.findAll()).thenReturn(List.of());
        when(jobs.countFromSourceSince(any(), any())).thenReturn(0L);
        when(jobs.countFromSourceSince(org.mockito.ArgumentMatchers.eq("remotive"), any())).thenReturn(7L);

        handler.onMessage(OWNER, "/sources");

        assertThat(gateway.texts()).singleElement().satisfies(text -> assertThat(text)
                .contains("<b>Remotive</b> — 🟢 on · not polled yet").contains("jobs in last 24h: 7")
                .contains("<b>hh.ru</b> — ⚪ off").contains("<b>Telegram channels</b> — ⚪ off"));
    }

    // ---- one-tap apply: approval by reply, buttons, edit mode ----------------------------------------------------

    private com.babakbaylarli.jobradar.persistence.ApplicationEntity application(com.babakbaylarli.jobradar.persistence.ApplicationStatus status, String platform) {
        JobEntity job = MessageFormatterTest.job(5, "Java Engineer", "Acme", "freelancer", 9, List.of("java"), null, "desc");
        var app = new com.babakbaylarli.jobradar.persistence.ApplicationEntity(5L, platform, "Hi, I can help. Which version do you run?", new java.math.BigDecimal("800"), "USD", 7,
                null, Instant.parse("2026-11-27T10:00:00Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(app, "id", 41L);
        org.springframework.test.util.ReflectionTestUtils.setField(app, "status", status);
        when(applications.jobOf(app)).thenReturn(job);
        when(applications.routeOf(app)).thenReturn(Optional.empty());
        when(applications.missingSetupFor(job)).thenReturn(Optional.empty());
        return app;
    }

    @Test
    void replyingOkToTheDraftApprovesThatApplication() {
        var app = application(com.babakbaylarli.jobradar.persistence.ApplicationStatus.DRAFT, "freelancer");
        when(applications.findByDraftMessage(900L)).thenReturn(Optional.of(app));
        when(applications.requestSend(41L)).thenReturn(new com.babakbaylarli.jobradar.apply.ApplicationService.SendDecision.Sent(app));

        handler.onMessage(OWNER, "ok", 900L);

        verify(applications).requestSend(41L);
        assertThat(gateway.texts()).singleElement().satisfies(t -> assertThat(t).contains("Sent"));
    }

    @Test
    void okInAnyLanguageSpellingCounts() {
        for (String word : List.of("ok", "OK", "Ok!", "ок", "ОК", "окей", "tamam", "Tamam.", " okay ")) {
            assertThat(BotUpdateHandler.isApproval(word)).as(word).isTrue();
        }
        for (String word : List.of("okx", "yes", "no", "send", "okay send it", "")) {
            assertThat(BotUpdateHandler.isApproval(word)).as(word).isFalse();
        }
    }

    @Test
    void okThatIsNotAReplyToADraftSendsNothing() {
        handler.onMessage(OWNER, "ok");
        handler.onMessage(OWNER, "ok", 123L); // a reply to some unrelated message

        verify(applications, never()).requestSend(org.mockito.ArgumentMatchers.anyLong());
        verify(applications, never()).confirmSend(org.mockito.ArgumentMatchers.anyLong());
        assertThat(gateway.texts()).hasSize(2).allSatisfy(t -> assertThat(t).contains("only understand commands"));
    }

    @Test
    void okWhileStillEditingDoesNotSend() {
        var app = application(com.babakbaylarli.jobradar.persistence.ApplicationStatus.EDITING, "freelancer");
        when(applications.findByDraftMessage(900L)).thenReturn(Optional.of(app));

        handler.onMessage(OWNER, "ok", 900L);

        verify(applications, never()).requestSend(org.mockito.ArgumentMatchers.anyLong());
        assertThat(gateway.texts()).singleElement().satisfies(t -> assertThat(t).contains("still editing"));
    }

    @Test
    void anApprovedLimitedActionAsksForTheFinalConfirmationAndOkOnThatMessageConfirmsIt() {
        var app = application(com.babakbaylarli.jobradar.persistence.ApplicationStatus.CONFIRMING, "freelancer");
        when(applications.findByDraftMessage(900L)).thenReturn(Optional.of(app));
        when(applications.requestSend(41L)).thenReturn(new com.babakbaylarli.jobradar.apply.ApplicationService.SendDecision.NeedsConfirmation(app, "This uses 1 of your Freelancer.com bids."));
        when(applications.confirmSend(41L)).thenReturn(new com.babakbaylarli.jobradar.apply.ApplicationService.SendDecision.Sent(app));

        handler.onCallback(OWNER, 1, "cb", "as:41");

        assertThat(gateway.sent).singleElement().satisfies(sent -> {
            assertThat(sent.html()).contains("This uses 1 of your Freelancer.com bids.").contains("Send application #41 now?");
            assertThat(sent.keyboard().rows().get(0)).extracting(Button::callbackData).containsExactly("ay:41", "an:41");
            verify(applications).attachDraftMessage(41L, sent.messageId());
        });

        handler.onMessage(OWNER, "ок", 900L); // reply "ok" to the confirmation message

        verify(applications).confirmSend(41L);
    }

    @Test
    void theYesAndNoButtonsOfTheConfirmation() {
        var app = application(com.babakbaylarli.jobradar.persistence.ApplicationStatus.DRAFT, "freelancer");
        when(applications.confirmSend(41L)).thenReturn(new com.babakbaylarli.jobradar.apply.ApplicationService.SendDecision.Sent(app));

        handler.onCallback(OWNER, 7, "cb1", "ay:41");
        handler.onCallback(OWNER, 8, "cb2", "an:41");

        verify(applications).confirmSend(41L);
        verify(applications).declineConfirmation(41L);
        assertThat(gateway.answers).containsExactly("Sending…", "Not sent");
        assertThat(gateway.texts()).anySatisfy(t -> assertThat(t).contains("Not sent"));
    }

    @Test
    void failedAndBlockedSendsAreShownWithRetryAndCopyOptions() {
        var app = application(com.babakbaylarli.jobradar.persistence.ApplicationStatus.FAILED, "freelancer");
        when(applications.requestSend(41L)).thenReturn(
                new com.babakbaylarli.jobradar.apply.ApplicationService.SendDecision.Failed(app, "Freelancer.com refused the bid: no bids left"),
                new com.babakbaylarli.jobradar.apply.ApplicationService.SendDecision.Blocked(app, "Daily limit reached: 10 of 10"));

        handler.onCallback(OWNER, 1, "cb", "as:41");
        handler.onCallback(OWNER, 1, "cb", "as:41");

        assertThat(gateway.sent).hasSize(2);
        assertThat(gateway.sent.get(0).html()).contains("Not sent").contains("no bids left").contains("Nothing was retried automatically");
        assertThat(gateway.sent.get(0).keyboard().rows().get(0)).extracting(Button::text).containsExactly("🔁 Retry", "📋 Copy & open");
        assertThat(gateway.sent.get(1).html()).contains("Daily limit reached");
    }

    @Test
    void editButtonStartsEditModeAndTheNextPlainMessageIsTheEdit() {
        var app = application(com.babakbaylarli.jobradar.persistence.ApplicationStatus.EDITING, "freelancer");
        when(applications.editing()).thenReturn(Optional.of(app));
        var edited = application(com.babakbaylarli.jobradar.persistence.ApplicationStatus.DRAFT, "freelancer");
        when(applications.applyEdit(41L, "shorter, mention Kafka")).thenReturn(edited);

        handler.onCallback(OWNER, 1, "cb", "ae:41");
        handler.onMessage(OWNER, "shorter, mention Kafka");

        verify(applications).beginEdit(41L);
        assertThat(gateway.texts().get(0)).contains("Edit mode").contains("bid 650 5");
        assertThat(gateway.texts().get(1)).contains("Draft").contains("Which version do you run?"); // the updated draft is shown again
        verify(applications, never()).requestSend(org.mockito.ArgumentMatchers.anyLong());               // editing never sends
    }

    @Test
    void anEditThatIsRejectedExplainsWhy() {
        var app = application(com.babakbaylarli.jobradar.persistence.ApplicationStatus.EDITING, "freelancer");
        when(applications.editing()).thenReturn(Optional.of(app));
        when(applications.applyEdit(41L, "ok then")).thenThrow(new com.babakbaylarli.jobradar.apply.ApplyException("That is too short to be a proposal."));

        handler.onMessage(OWNER, "ok then");

        assertThat(gateway.texts()).singleElement().satisfies(t -> assertThat(t).contains("too short"));
    }

    @Test
    void cancelCopyAndRegenerateButtons() {
        var app = application(com.babakbaylarli.jobradar.persistence.ApplicationStatus.COPIED, "copy");
        var regenerated = application(com.babakbaylarli.jobradar.persistence.ApplicationStatus.DRAFT, "copy");
        when(applications.copyAndOpen(41L)).thenReturn(app);
        when(applications.regenerate(41L)).thenReturn(regenerated);

        handler.onCallback(OWNER, 3, "c1", "ac:41");
        handler.onCallback(OWNER, 4, "c2", "ak:41");
        handler.onCallback(OWNER, 5, "c3", "ar:41");

        verify(applications).cancel(41L);
        assertThat(gateway.edits).extracting(RecordingGateway.Edit::messageId).contains(3, 4);
        assertThat(gateway.texts()).anySatisfy(t -> assertThat(t).contains("Copy &amp; open").contains("<pre>Hi, I can help."));
        await().untilAsserted(() -> verify(applications).regenerate(41L));
    }

    @Test
    void applicationErrorsReachTheOwnerAndStrangersCannotUseTheButtons() {
        when(applications.requestSend(41L)).thenThrow(new com.babakbaylarli.jobradar.apply.ApplyException("This application is already sent, so that action is not available."));

        handler.onCallback(OWNER, 1, "cb", "as:41");
        handler.onCallback(STRANGER, 1, "cb2", "as:41");

        assertThat(gateway.texts()).singleElement().satisfies(t -> assertThat(t).contains("already sent"));
        assertThat(gateway.answers).containsExactly("Checking…", "Not possible", "Private bot");
        verify(applications).requestSend(41L); // only the owner's press reached the service
    }

    @Test
    void appliedAndLimitsCommands() {
        var sentApp = application(com.babakbaylarli.jobradar.persistence.ApplicationStatus.SENT, "freelancer");
        when(applications.recent(20)).thenReturn(List.of(sentApp));
        when(jobs.findById(5L)).thenReturn(Optional.of(MessageFormatterTest.job(5, "Java Engineer", "Acme", "freelancer", 9, List.of(), null, "d")));
        when(applications.limits()).thenReturn(new com.babakbaylarli.jobradar.apply.ApplicationService.LimitsView(2, 10, java.util.Map.of("freelancer", 2L),
                java.util.Map.of("freelancer", 5), java.time.Duration.ofMinutes(2), Optional.empty()));

        handler.onMessage(OWNER, "/applied");
        handler.onMessage(OWNER, "/limits");

        assertThat(gateway.texts().get(0)).contains("Last applications").contains("<b>Java Engineer</b> · sent · freelancer");
        assertThat(gateway.texts().get(1)).contains("Total: <b>2 / 10</b>").contains("freelancer: 2 / 5").contains("Copy &amp; open does not count");
    }

    @Test
    void strangersCannotApproveAnythingEvenByReplyingOk() {
        handler.onMessage(STRANGER, "ok", 900L);

        verify(applications, never()).findByDraftMessage(org.mockito.ArgumentMatchers.anyLong());
        verify(applications, never()).requestSend(org.mockito.ArgumentMatchers.anyLong());
        assertThat(gateway.texts()).containsExactly(BotUpdateHandler.PRIVATE_BOT);
    }
}
