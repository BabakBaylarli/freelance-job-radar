package com.babakbaylarli.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;

import com.babakbaylarli.jobradar.TestSupport;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.source.Job;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Real SMTP protocol against GreenMail. */
class EmailSenderTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(com.icegreen.greenmail.configuration.GreenMailConfiguration.aConfig().withUser("me@example.org", "app-password"));

    @TempDir
    Path dir;

    private EmailSender sender(boolean credentials, String cvPath) {
        var email = new JobRadarProperties.EmailAlerts(false, Duration.ofMinutes(15), "imap.invalid", 993, true,
                credentials ? "me@example.org" : null, credentials ? "app-password" : null, "JobAlerts", 30);
        JobRadarProperties withMail = TestSupport.withEmailAlerts(TestSupport.props("http://unused"), email);
        var apply = new JobRadarProperties.Apply(10, Map.of(), Duration.ofMinutes(2), Map.of(), Map.of(), 7,
                List.of("https://github.com/example/ledger-service", "https://github.com/example/radar"), cvPath, ZoneId.of("Asia/Baku"),
                new JobRadarProperties.Smtp("127.0.0.1", greenMail.getSmtp().getPort(), false, false), null);
        return new EmailSender(TestSupport.withApply(withMail, apply));
    }

    private static Job job(String description) {
        return new Job("dou", "381243", "Senior Java Developer", "Example Software", "https://jobs.example/381243", List.of(), description, null, null);
    }

    @Test
    void sendsSubjectProposalAndPortfolioLinksToTheContactNamedInThePosting() throws Exception {
        EmailSender sender = sender(true, dir.resolve("none.pdf").toString());
        Job job = job("Send your CV to hr@acme-example.com. noreply@acme-example.com is not monitored.");
        assertThat(sender.recipient(job)).contains("hr@acme-example.com");

        SendResult result = sender.send(new SendRequest(job, "Hello, I can help with your payments ledger. Which Kafka version do you run?", null, "hr@acme-example.com"));

        assertThat(result.ok()).isTrue();
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getSubject()).isEqualTo("Application: Senior Java Developer");
        assertThat(received[0].getFrom()[0].toString()).isEqualTo("me@example.org");
        assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("hr@acme-example.com");
        String body = bodyOf(received[0]);
        assertThat(body).contains("Which Kafka version do you run?").contains("Portfolio:").contains("- https://github.com/example/ledger-service");
    }

    @Test
    void attachesTheCvOnlyWhenTheFileExists() throws Exception {
        Path cv = dir.resolve("cv.pdf");
        Files.writeString(cv, "%PDF-1.4 fake cv");
        EmailSender sender = sender(true, cv.toString());

        SendResult result = sender.send(new SendRequest(job("hr@acme-example.com"), "Proposal text", null, "hr@acme-example.com"));

        assertThat(result.ok()).isTrue();
        MimeMessage message = greenMail.getReceivedMessages()[0];
        MimeMultipart multipart = (MimeMultipart) message.getContent();
        boolean hasPdf = false;
        for (int i = 0; i < multipart.getCount(); i++) {
            hasPdf |= "cv.pdf".equals(multipart.getBodyPart(i).getFileName());
        }
        assertThat(hasPdf).isTrue();
    }

    @Test
    void anSmtpFailureIsAVisibleFailureNotAnException() {
        var email = new JobRadarProperties.EmailAlerts(false, Duration.ofMinutes(15), "imap.invalid", 993, true, "me@example.org", "wrong-password", "JobAlerts", 30);
        var apply = new JobRadarProperties.Apply(10, Map.of(), Duration.ofMinutes(2), Map.of(), Map.of(), 7, List.of(), "x.pdf", ZoneId.of("UTC"),
                new JobRadarProperties.Smtp("127.0.0.1", greenMail.getSmtp().getPort(), false, false), null);
        EmailSender sender = new EmailSender(TestSupport.withApply(TestSupport.withEmailAlerts(TestSupport.props("http://unused"), email), apply));

        SendResult result = sender.send(new SendRequest(job("hr@acme-example.com"), "text", null, "hr@acme-example.com"));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).startsWith("Could not send the e-mail");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void needsCredentialsAndAContactAddress() {
        assertThat(sender(false, "x").unavailableReason()).hasValueSatisfying(r -> assertThat(r).contains("MAIL_USERNAME"));
        assertThat(sender(true, "x").unavailableReason()).isEmpty();
        assertThat(sender(true, "x").supports(job("no contact in this posting"))).isFalse();
        assertThat(sender(true, "x").send(new SendRequest(job("x"), "text", null, null)).ok()).isFalse();
    }

    private static String bodyOf(MimeMessage message) throws Exception {
        Object content = message.getContent();
        if (content instanceof MimeMultipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                Object part = multipart.getBodyPart(i).getContent();
                if (part instanceof String text) {
                    return text;
                }
                if (part instanceof MimeMultipart nested) {
                    for (int j = 0; j < nested.getCount(); j++) {
                        if (nested.getBodyPart(j).getContent() instanceof String text) {
                            return text;
                        }
                    }
                }
            }
        }
        return String.valueOf(content);
    }
}
