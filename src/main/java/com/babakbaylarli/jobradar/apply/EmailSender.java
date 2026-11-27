package com.babakbaylarli.jobradar.apply;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.source.HtmlText;
import com.babakbaylarli.jobradar.source.Job;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends the approved proposal from the owner's own Gmail (SMTP + the same App Password as the IMAP alerts) to the contact
 * address the posting itself names (typical for DOU, Telegram and Hacker News posts). Subject, proposal and portfolio links;
 * an optional {@code cv.pdf} is attached when the configured file exists. Never retried, never logs the message.
 */
@Component
public class EmailSender implements ProposalSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final JobRadarProperties.EmailAlerts mail;
    private final JobRadarProperties.Apply apply;

    public EmailSender(JobRadarProperties properties) {
        this.mail = properties.sources().emailAlerts();
        this.apply = properties.apply();
    }

    @Override
    public String platform() {
        return "email";
    }

    @Override
    public String route() {
        return "e-mail from your own address";
    }

    @Override
    public boolean supports(Job job) {
        return recipient(job).isPresent();
    }

    @Override
    public Optional<String> unavailableReason() {
        return mail.credentialsPresent() ? Optional.empty() : Optional.of("MAIL_USERNAME / MAIL_APP_PASSWORD are not set");
    }

    @Override
    public Optional<String> recipient(Job job) {
        return ContactFinder.email(job.title() + "\n" + job.description(), mail.username());
    }

    @Override
    public SendResult send(SendRequest request) {
        if (request.recipient() == null || request.recipient().isBlank()) {
            return SendResult.failed("No contact e-mail address was found in this posting.");
        }
        try {
            JavaMailSenderImpl sender = sender();
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mail.username());
            helper.setTo(request.recipient());
            helper.setSubject(HtmlText.truncate("Application: " + request.job().title(), 150));
            helper.setText(body(request));
            Path cv = Path.of(apply.cvPath());
            if (Files.isRegularFile(cv)) {
                helper.addAttachment(cv.getFileName().toString(), new FileSystemResource(cv));
            }
            sender.send(message);
            log.info("event=application_sent platform=email recipient_domain={}", domain(request.recipient()));
            return SendResult.ok(message.getMessageID());
        } catch (MailException | MessagingException e) {
            return SendResult.failed("Could not send the e-mail: " + rootMessage(e));
        }
    }

    private String body(SendRequest request) {
        StringBuilder sb = new StringBuilder(request.text().strip());
        if (!apply.portfolioLinks().isEmpty()) {
            sb.append("\n\nPortfolio:");
            apply.portfolioLinks().forEach(link -> sb.append("\n- ").append(link));
        }
        return sb.toString();
    }

    private JavaMailSenderImpl sender() {
        JobRadarProperties.Smtp smtp = apply.smtp();
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        sender.setUsername(mail.username());
        sender.setPassword(mail.appPassword());
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", String.valueOf(smtp.starttls()));
        props.put("mail.smtp.ssl.enable", String.valueOf(smtp.ssl()));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "20000");
        props.put("mail.smtp.writetimeout", "20000");
        return sender;
    }

    private static String domain(String address) {
        int at = address.lastIndexOf('@');
        return at < 0 ? "?" : address.substring(at + 1);
    }

    private static String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
