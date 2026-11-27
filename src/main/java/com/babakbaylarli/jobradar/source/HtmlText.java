package com.babakbaylarli.jobradar.source;

import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/** Converts the HTML snippets found in feed descriptions into readable plain text (no network access). */
public final class HtmlText {

    /** Tags that start a new paragraph (blank line before and after). */
    private static final Set<String> PARAGRAPH_TAGS = Set.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote");
    /** Tags that just start a new line. */
    private static final Set<String> LINE_TAGS = Set.of("div", "ul", "ol", "li", "tr");

    private HtmlText() {}

    public static String toPlain(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    out.append(text.getWholeText());
                } else if (node instanceof Element el) {
                    String tag = el.normalName();
                    if (tag.equals("br")) {
                        out.append('\n');
                    } else if (PARAGRAPH_TAGS.contains(tag)) {
                        breakLine(out, 2);
                    } else if (LINE_TAGS.contains(tag)) {
                        breakLine(out, 1);
                        if (tag.equals("li")) {
                            out.append("- ");
                        }
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element el) {
                    if (PARAGRAPH_TAGS.contains(el.normalName())) {
                        breakLine(out, 2);
                    } else if (LINE_TAGS.contains(el.normalName())) {
                        breakLine(out, 1);
                    }
                }
            }
        }, Jsoup.parseBodyFragment(html).body());
        return normalizeWhitespace(out.toString());
    }

    /** Ensure the buffer ends with (at least) the given number of newlines, without piling up extra ones. */
    private static void breakLine(StringBuilder sb, int newlines) {
        if (sb.isEmpty()) {
            return;
        }
        int trailing = 0;
        for (int i = sb.length() - 1; i >= 0 && sb.charAt(i) == '\n'; i--) {
            trailing++;
        }
        for (int i = trailing; i < newlines; i++) {
            sb.append('\n');
        }
    }

    static String normalizeWhitespace(String text) {
        String[] lines = text.replace(' ', ' ').split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int blank = 0;
        for (String raw : lines) {
            String line = raw.replaceAll("[ \\t]+", " ").strip();
            if (line.isEmpty()) {
                blank++;
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(blank > 0 ? "\n\n" : "\n");
            }
            blank = 0;
            sb.append(line);
        }
        return sb.toString();
    }

    /** First line of a text, truncated on a word boundary. */
    public static String firstLine(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String first = text.strip().lines().findFirst().orElse("");
        return truncate(first, maxLength);
    }

    public static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        int cut = text.lastIndexOf(' ', maxLength - 1);
        if (cut < maxLength / 2) {
            cut = maxLength - 1;
        }
        return text.substring(0, cut).stripTrailing() + "…";
    }
}
