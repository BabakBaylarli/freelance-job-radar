package com.babakbaylarli.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.babakbaylarli.jobradar.persistence.ApplicationEntity;
import com.babakbaylarli.jobradar.persistence.ApplicationStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ApplicationStatusTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    private static ApplicationEntity app() {
        return new ApplicationEntity(1L, "freelancer", "text", null, null, null, null, NOW);
    }

    @Test
    void terminalStatesCannotBeLeft() {
        for (ApplicationStatus terminal : new ApplicationStatus[] {ApplicationStatus.SENT, ApplicationStatus.COPIED, ApplicationStatus.CANCELLED}) {
            for (ApplicationStatus next : ApplicationStatus.values()) {
                assertThat(terminal.canMoveTo(next)).as(terminal + " -> " + next).isFalse();
            }
        }
    }

    @Test
    void theHappyPathsAreAllowed() {
        assertThat(ApplicationStatus.DRAFT.canMoveTo(ApplicationStatus.EDITING)).isTrue();
        assertThat(ApplicationStatus.EDITING.canMoveTo(ApplicationStatus.DRAFT)).isTrue();
        assertThat(ApplicationStatus.DRAFT.canMoveTo(ApplicationStatus.CONFIRMING)).isTrue();
        assertThat(ApplicationStatus.CONFIRMING.canMoveTo(ApplicationStatus.SENT)).isTrue();
        assertThat(ApplicationStatus.CONFIRMING.canMoveTo(ApplicationStatus.DRAFT)).isTrue(); // "no" to the confirmation
        assertThat(ApplicationStatus.FAILED.canMoveTo(ApplicationStatus.SENT)).isTrue();       // retry
        assertThat(ApplicationStatus.FAILED.canMoveTo(ApplicationStatus.COPIED)).isTrue();     // copy & open after a failure
    }

    @Test
    void youCannotSkipTheEditOrConfirmationSteps() {
        assertThat(ApplicationStatus.EDITING.canMoveTo(ApplicationStatus.SENT)).isFalse();      // must approve the edited draft first
        assertThat(ApplicationStatus.EDITING.canMoveTo(ApplicationStatus.CONFIRMING)).isFalse();
        assertThat(ApplicationStatus.CONFIRMING.canMoveTo(ApplicationStatus.EDITING)).isFalse();
    }

    @Test
    void entityRejectsIllegalMovesAndRecordsSentDetails() {
        ApplicationEntity sent = app();
        sent.markSent("bid-77", NOW.plusSeconds(5));

        assertThat(sent.getStatus()).isEqualTo(ApplicationStatus.SENT);
        assertThat(sent.getResponseId()).isEqualTo("bid-77");
        assertThat(sent.getSentAt()).isEqualTo(NOW.plusSeconds(5));
        assertThatThrownBy(() -> sent.moveTo(ApplicationStatus.DRAFT, NOW)).isInstanceOf(IllegalStateException.class).hasMessageContaining("SENT -> DRAFT");
        assertThatThrownBy(() -> sent.markFailed("x", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void openStatesAreTheOnesTheOwnerCanStillAct() {
        assertThat(ApplicationStatus.DRAFT.isOpen()).isTrue();
        assertThat(ApplicationStatus.FAILED.isOpen()).isTrue();
        assertThat(ApplicationStatus.SENT.isOpen()).isFalse();
        assertThat(ApplicationStatus.CANCELLED.isOpen()).isFalse();
    }
}
