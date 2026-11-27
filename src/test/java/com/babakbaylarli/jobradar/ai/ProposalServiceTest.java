package com.babakbaylarli.jobradar.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.babakbaylarli.jobradar.TestSupport;
import com.babakbaylarli.jobradar.ai.ProposalService.Outcome;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.source.Job;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProposalServiceTest {

    private static final Job JOB = new Job("remotive", "1", "Java Engineer", "Acme", "https://x.test/1",
            List.of("java"), "Build payments APIs", null, Instant.EPOCH);

    @TempDir
    Path dir;

    private ProfileLoader profile(String content) throws Exception {
        Path file = dir.resolve("profile.md");
        if (content != null) {
            Files.writeString(file, content);
        }
        JobRadarProperties base = TestSupport.props("http://unused");
        return new ProfileLoader(new JobRadarProperties(base.telegram(), base.ai(), base.matching(), base.notifications(),
                base.http(), base.sources(), file.toString(), base.apply()));
    }

    @Test
    void withoutApiKeyTheServiceIsDisabledAndNeverTouchesTheProfile() throws Exception {
        ProposalService service = new ProposalService(ProposalGenerator.disabled(), new ProposalPromptBuilder(), profile("x"));

        assertThat(service.aiEnabled()).isFalse();
        assertThat(service.draft(JOB)).isInstanceOf(Outcome.Disabled.class);
    }

    @Test
    void draftsFromProfileAndJob() throws Exception {
        ProposalGenerator generator = mock(ProposalGenerator.class);
        when(generator.available()).thenReturn(true);
        when(generator.generate(any())).thenReturn("Dear Acme ...?");
        ProposalService service = new ProposalService(generator, new ProposalPromptBuilder(), profile("# Me\n2+ years banking backend"));

        Outcome outcome = service.draft(JOB);

        assertThat(outcome).isEqualTo(new Outcome.Drafted("Dear Acme ...?"));
        org.mockito.ArgumentCaptor<PromptParts> captor = org.mockito.ArgumentCaptor.forClass(PromptParts.class);
        verify(generator).generate(captor.capture());
        assertThat(captor.getValue().system()).contains("2+ years banking backend");
        assertThat(captor.getValue().user()).contains("Java Engineer");
    }

    @Test
    void missingProfileFailsClearlyWithoutCallingTheModel() throws Exception {
        ProposalGenerator generator = mock(ProposalGenerator.class);
        when(generator.available()).thenReturn(true);
        ProposalService service = new ProposalService(generator, new ProposalPromptBuilder(), profile(null));

        Outcome outcome = service.draft(JOB);

        assertThat(outcome).isInstanceOfSatisfying(Outcome.Failed.class,
                failed -> assertThat(failed.message()).contains("profile").contains("profile.example.md"));
        verify(generator, never()).generate(any());
    }

    @Test
    void modelFailuresBecomeFailedOutcomes() throws Exception {
        ProposalGenerator generator = mock(ProposalGenerator.class);
        when(generator.available()).thenReturn(true);
        when(generator.generate(any())).thenThrow(new ProposalException("The AI request failed: 529 overloaded"));
        ProposalService service = new ProposalService(generator, new ProposalPromptBuilder(), profile("p"));

        assertThat(service.draft(JOB)).isEqualTo(new Outcome.Failed("The AI request failed: 529 overloaded"));
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        ProposalGenerator generator = mock(ProposalGenerator.class);
        when(generator.available()).thenReturn(true);
        when(generator.generate(any())).thenThrow(new IllegalStateException("secret internal detail"));
        ProposalService service = new ProposalService(generator, new ProposalPromptBuilder(), profile("p"));

        assertThat(service.draft(JOB)).isInstanceOfSatisfying(Outcome.Failed.class,
                failed -> assertThat(failed.message()).doesNotContain("secret"));
    }
}
