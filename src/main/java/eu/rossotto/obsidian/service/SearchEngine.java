package eu.rossotto.obsidian.service;

import eu.rossotto.obsidian.vault.IndexedContent;
import eu.rossotto.obsidian.vault.Note;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Case-insensitive full-text search over the <em>rendered</em> content of visible notes, so hidden link targets,
 * image paths and comments are never searchable.
 *
 * <p>Query syntax: whitespace separated terms, {@code "quoted phrases"}. A note matches when every term appears in
 * its title, tags, path or content. Ranking favours title, then tag, then path matches, then content frequency.
 */
public final class SearchEngine {

    private static final Pattern TERM = Pattern.compile("\"([^\"]+)\"|(\\S+)");
    private static final int SNIPPET_RADIUS = 90;
    private static final int MAX_SNIPPETS = 3;

    private SearchEngine() {
    }

    public record Candidate(Note note, IndexedContent content) {
    }

    public record Hit(Note note, double score, List<String> snippets) {
    }

    public static List<String> parse(String query) {
        List<String> terms = new ArrayList<>();
        if (query == null) {
            return terms;
        }
        Matcher m = TERM.matcher(query);
        while (m.find()) {
            String t = (m.group(1) != null ? m.group(1) : m.group(2)).strip().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                terms.add(t);
            }
        }
        return terms;
    }

    public static List<Hit> search(String query, List<Candidate> candidates, int limit) {
        List<String> terms = parse(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (Candidate c : candidates) {
            String title = c.note().title().toLowerCase(Locale.ROOT);
            String path = c.note().path().toLowerCase(Locale.ROOT);
            String tags = String.join(" ", c.note().tags()).toLowerCase(Locale.ROOT);
            String body = c.content().lowerContent();
            double score = 0;
            boolean all = true;
            for (String term : terms) {
                double s = 0;
                if (title.contains(term)) {
                    s += title.equals(term) ? 20 : 10;
                }
                if (tags.contains(term)) {
                    s += 5;
                }
                if (path.contains(term)) {
                    s += 2;
                }
                int occurrences = count(body, term, 50);
                s += occurrences == 0 ? 0 : 1 + Math.log(occurrences);
                if (s == 0) {
                    all = false;
                    break;
                }
                score += s;
            }
            if (all) {
                hits.add(new Hit(c.note(), Math.round(score * 100) / 100.0,
                        snippets(c.content().rendered().content(), body, terms)));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(h -> h.note().id()));
        return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : hits;
    }

    private static int count(String haystack, String needle, int cap) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0 && n < cap) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    private static List<String> snippets(String original, String lower, List<String> terms) {
        List<String> out = new ArrayList<>();
        int coveredUntil = -1;
        for (String term : terms) {
            int i = lower.indexOf(term);
            while (i >= 0 && out.size() < MAX_SNIPPETS) {
                if (i > coveredUntil && i < original.length()) {
                    // lower-casing can (rarely) shift offsets, so clamp to the original text
                    int from = Math.max(0, i - SNIPPET_RADIUS);
                    int to = Math.min(original.length(), i + term.length() + SNIPPET_RADIUS);
                    String s = original.substring(from, to).replaceAll("\\s+", " ").strip();
                    out.add((from > 0 ? "…" : "") + s + (to < original.length() ? "…" : ""));
                    coveredUntil = to;
                    break;
                }
                i = lower.indexOf(term, i + term.length());
            }
            if (out.size() >= MAX_SNIPPETS) {
                break;
            }
        }
        return out;
    }
}
