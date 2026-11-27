package com.babakbaylarli.jobradar.source.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.babakbaylarli.jobradar.TestSupport;
import com.babakbaylarli.jobradar.ai.AiJobExtractor;
import com.babakbaylarli.jobradar.ai.ProposalGenerator;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.config.JobRadarProperties.EmailAlerts;
import com.babakbaylarli.jobradar.source.Job;
import com.babakbaylarli.jobradar.source.Market;
import com.babakbaylarli.jobradar.source.SourceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.store.StoredMessage;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Flags;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Real IMAP protocol against GreenMail: only the alert folder is read, mail stays unread until the jobs are stored. */
class EmailAlertSourceTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP.dynamicPort());

    private GreenMailUser user;

    @BeforeEach
    void setUp() throws Exception {
        user = greenMail.setUser("me@example.org", "me", "app-password");
        greenMail.getManagers().getImapHostManager().createMailbox(user, "JobAlerts");
    }

    private void deliver(String folderName, String sample) throws Exception {
        MailFolder folder = greenMail.getManagers().getImapHostManager().getFolder(user, folderName);
        folder.appendMessage(TestSupport.sampleMail(sample), new Flags(), new Date());
    }

    private List<StoredMessage> messages(String folderName) {
        return greenMail.getManagers().getImapHostManager().getFolder(user, folderName).getMessages();
    }

    private EmailAlertSource source(String folder, AiJobExtractor extractor) {
        EmailAlerts config = new EmailAlerts(true, Duration.ofMinutes(15), "127.0.0.1", greenMail.getImap().getPort(), false, "me", "app-password", folder, 30);
        JobRadarProperties props = TestSupport.withEmailAlerts(TestSupport.props("http://unused"), config);
        return new EmailAlertSource(props, extractor);
    }

    private AiJobExtractor noAi() {
        return new AiJobExtractor(ProposalGenerator.disabled(), new ObjectMapper());
    }

    @Test
    void readsOnlyTheAlertFolderAndLeavesTheInboxUntouched() throws Exception {
        deliver("INBOX", "newsletter-noise");
        deliver("INBOX", "upwork-alert"); // a real-looking alert in the INBOX must NOT be read
        deliver("JobAlerts", "djinni-digest");
        deliver("JobAlerts", "kwork-alert");
        EmailAlertSource source = source("JobAlerts", noAi());

        List<Job> jobs = source.fetch();

        assertThat(jobs).extracting(Job::source).containsOnly("email:djinni", "email:kwork");
        assertThat(jobs).hasSize(4);
        assertThat(messages("INBOX")).noneMatch(m -> m.isSet(Flags.Flag.SEEN));
        source.closeQuietly();
    }

    @Test
    void fetchingDoesNotMarkMailReadUntilTheJobsAreStored() throws Exception {
        deliver("JobAlerts", "freelancer-alert");
        EmailAlertSource source = source("JobAlerts", noAi());

        source.fetch();
        assertThat(messages("JobAlerts")).noneMatch(m -> m.isSet(Flags.Flag.SEEN)); // peeked, not read

        source.afterIngest();
        assertThat(messages("JobAlerts")).allMatch(m -> m.isSet(Flags.Flag.SEEN));
        assertThat(source.fetch()).isEmpty(); // nothing unread any more
        source.closeQuietly();
    }

    @Test
    void ifTheJobsWereNeverStoredTheMailIsStillUnreadOnTheNextPoll() throws Exception {
        deliver("JobAlerts", "guru-alert");
        EmailAlertSource source = source("JobAlerts", noAi());

        assertThat(source.fetch()).hasSize(1);
        // ingest failed: afterIngest() is never called
        assertThat(source.fetch()).hasSize(1);
        source.closeQuietly();
    }

    @Test
    void anUnknownSenderBecomesOneJobFromSubjectAndFirstRealLink() throws Exception {
        deliver("JobAlerts", "bossaz-alert");
        EmailAlertSource source = source("JobAlerts", noAi());

        List<Job> jobs = source.fetch();

        assertThat(jobs).hasSize(1);
        Job job = jobs.get(0);
        assertThat(job.title()).isEqualTo("Yeni vakansiyalar: Java");
        assertThat(job.url()).isEqualTo("https://boss.az/vacancies/998877");
        assertThat(job.source()).isEqualTo("email:boss.az");
        assertThat(job.market()).isEqualTo(Market.AZ);
        source.closeQuietly();
    }

    @Test
    void anUnknownSenderIsExtractedByAiWhenEnabled() throws Exception {
        deliver("JobAlerts", "bossaz-alert");
        ProposalGenerator generator = mock(ProposalGenerator.class);
        when(generator.available()).thenReturn(true);
        when(generator.generate(any())).thenReturn("{\"jobs\":[{\"title\":\"Java proqramçı (uzaqdan)\",\"url\":\"https://boss.az/vacancies/998877\","
                + "\"company\":\"Example MMC\"},{\"title\":\"Mühasib\",\"url\":\"https://boss.az/vacancies/998878\"}]}");
        EmailAlertSource source = source("JobAlerts", new AiJobExtractor(generator, new ObjectMapper()));

        List<Job> jobs = source.fetch();

        assertThat(jobs).extracting(Job::title).containsExactly("Java proqramçı (uzaqdan)", "Mühasib");
        assertThat(jobs.get(0).company()).isEqualTo("Example MMC");
        assertThat(jobs.get(0).market()).isEqualTo(Market.AZ);
        source.closeQuietly();
    }

    @Test
    void aMailWithNoJobLinkProducesNothingButIsStillMarkedRead() throws Exception {
        deliver("JobAlerts", "newsletter-noise");
        EmailAlertSource source = source("JobAlerts", noAi());

        assertThat(source.fetch()).isEmpty();
        source.afterIngest();

        assertThat(messages("JobAlerts")).allMatch(m -> m.isSet(Flags.Flag.SEEN));
        source.closeQuietly();
    }

    @Test
    void aMissingLabelGivesAHelpfulError() {
        EmailAlertSource source = source("DoesNotExist", noAi());

        assertThatThrownBy(source::fetch).isInstanceOf(SourceException.class)
                .hasMessageContaining("DoesNotExist").hasMessageContaining("create the label");
    }

    @Test
    void wrongCredentialsAreASourceErrorNotACrash() {
        EmailAlerts config = new EmailAlerts(true, Duration.ofMinutes(15), "127.0.0.1", greenMail.getImap().getPort(), false, "me", "wrong", "JobAlerts", 30);
        EmailAlertSource source = new EmailAlertSource(TestSupport.withEmailAlerts(TestSupport.props("http://unused"), config), noAi());

        assertThatThrownBy(source::fetch).isInstanceOf(SourceException.class).hasMessageContaining("IMAP");
    }

    @Test
    void isSkippedWithAReasonWhenCredentialsAreMissing() {
        EmailAlerts config = new EmailAlerts(true, Duration.ofMinutes(15), "imap.gmail.com", 993, true, null, null, "JobAlerts", 30);
        EmailAlertSource source = new EmailAlertSource(TestSupport.withEmailAlerts(TestSupport.props("http://unused"), config), noAi());

        assertThat(source.unavailableReason()).hasValueSatisfying(r -> assertThat(r).contains("MAIL_USERNAME"));
        assertThat(source.id()).isEqualTo("email");
    }
}
