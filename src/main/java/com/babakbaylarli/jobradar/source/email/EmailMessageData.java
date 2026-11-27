package com.babakbaylarli.jobradar.source.email;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;

/** The parts of a job-alert e-mail the parsers need. Bodies are processed in memory and never logged. */
public record EmailMessageData(String fromAddress, String subject, String html, String text, Instant received, String messageId) {

    private static final int MAX_BODY_CHARS = 400_000;

    public String fromDomain() {
        int at = fromAddress.lastIndexOf('@');
        return at < 0 ? "" : fromAddress.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    /** All hyperlinks of the HTML body. */
    public Set<String> links() {
        if (html == null || html.isBlank()) {
            return Set.of();
        }
        return Jsoup.parse(html).select("a[href]").stream().map(a -> a.attr("href").strip())
                .filter(h -> h.startsWith("http://") || h.startsWith("https://")).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public static EmailMessageData from(MimeMessage message) throws MessagingException, IOException {
        Address[] from = message.getFrom();
        String address = from != null && from.length > 0 && from[0] instanceof InternetAddress ia ? ia.getAddress() : "";
        Bodies bodies = new Bodies();
        collect(message, bodies);
        return new EmailMessageData(address == null ? "" : address.toLowerCase(Locale.ROOT), message.getSubject() == null ? "" : message.getSubject(),
                cut(bodies.html), cut(bodies.text), message.getReceivedDate() == null ? null : message.getReceivedDate().toInstant(),
                message.getMessageID());
    }

    private static final class Bodies {
        String html;
        String text;
    }

    private static void collect(Part part, Bodies bodies) throws MessagingException, IOException {
        if (part.isMimeType("text/html")) {
            if (bodies.html == null) {
                bodies.html = String.valueOf(part.getContent());
            }
        } else if (part.isMimeType("text/plain")) {
            if (bodies.text == null) {
                bodies.text = String.valueOf(part.getContent());
            }
        } else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                if (!Part.ATTACHMENT.equalsIgnoreCase(child.getDisposition())) {
                    collect(child, bodies);
                }
            }
        }
    }

    private static String cut(String body) {
        return body == null ? null : body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) : body;
    }
}
