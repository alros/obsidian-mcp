package eu.rossotto.obsidian.vault;

import eu.rossotto.obsidian.vault.RenderedNote.ExternalLink;
import eu.rossotto.obsidian.vault.RenderedNote.NoteRef;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the raw markdown body of a note into what the model receives.
 *
 * <ul>
 *   <li>Images ({@code ![[x.png]]}, {@code ![alt](x.png)}, {@code <img>}) become {@code [image: name]}.</li>
 *   <li>Other attachments become {@code [attachment: name]}.</li>
 *   <li>Links and embeds pointing to visible notes are kept, and listed in {@code linkedNotes}.</li>
 *   <li>Links and embeds pointing to hidden or missing notes are flattened to their display text.</li>
 *   <li>External http(s)/ftp links are kept and listed in {@code links}.</li>
 *   <li>Links using other URI schemes (e.g. {@code obsidian://}) are flattened to their text.</li>
 *   <li>Obsidian comments ({@code %%...%%}) are removed, they are invisible in Obsidian too.</li>
 *   <li>Code blocks and inline code are left untouched.</li>
 * </ul>
 */
public final class NoteRenderer {

    static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "svg", "webp", "avif", "heic", "heif", "tif", "tiff", "ico");

    private static final Pattern FENCE_OPEN = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");
    private static final Pattern COMMENT = Pattern.compile("%%.*?%%", Pattern.DOTALL);
    private static final Pattern INLINE_CODE = Pattern.compile("(`+)(?!`).*?(?<!`)\\1(?!`)", Pattern.DOTALL);

    private static final String DEST_TAIL = "(?:\\s+(?:\"[^\"\\n]*\"|'[^'\\n]*'))?\\s*\\)";
    private static final String DEST_BODY = "\\s*(?:<[^>\\n]*>|(?:[^()\\s]|\\([^()\\s]*\\))*)";

    private static final Pattern TOKENS = Pattern.compile(
            "!\\[\\[(?<embed>[^\\[\\]\\n]+?)]]"
                    + "|\\[\\[(?<wiki>[^\\[\\]\\n]+?)]]"
                    + "|!\\[(?<ialt>[^\\]\\n]*)]\\((?<idest>" + DEST_BODY + ")" + DEST_TAIL
                    + "|\\[(?<ltext>(?:!\\[[^\\]\\n]*]\\([^)\\n]*\\)|[^\\]\\n])*)]\\((?<ldest>" + DEST_BODY + ")" + DEST_TAIL
                    + "|(?<img><img\\b[^>]*>)"
                    + "|<(?<auto>(?:https?|ftp)://[^>\\s]+)>"
                    + "|(?<bare>\\b(?:https?|ftp)://[^\\s<>\\[\\]\"'`]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern IMG_ALT = Pattern.compile("\\balt\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG_SRC = Pattern.compile("\\bsrc\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_SIZE = Pattern.compile("\\d+(x\\d+)?");
    private static final Pattern EXTERNAL_SCHEME = Pattern.compile("^(?:https?|ftp)://.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*:.*", Pattern.CASE_INSENSITIVE);

    private NoteRenderer() {
    }

    public static RenderedNote render(Note source, String body, LinkResolver resolver) {
        Context ctx = new Context(source, resolver);
        StringBuilder out = new StringBuilder(body.length());
        StringBuilder prose = new StringBuilder();
        String[] lines = body.split("\n", -1);
        String fence = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String nl = i < lines.length - 1 ? "\n" : "";
            if (fence == null) {
                Matcher m = FENCE_OPEN.matcher(line);
                if (m.matches()) {
                    flushProse(prose, out, ctx);
                    fence = m.group(1);
                    out.append(line).append(nl);
                } else {
                    prose.append(line).append(nl);
                }
            } else {
                out.append(line).append(nl);
                String t = line.strip();
                char fenceChar = fence.charAt(0);
                if (t.length() >= fence.length() && t.chars().allMatch(c -> c == fenceChar)) {
                    fence = null;
                }
            }
        }
        flushProse(prose, out, ctx);
        return new RenderedNote(out.toString().strip(),
                List.copyOf(ctx.links.values()), List.copyOf(ctx.notes.values()));
    }

    private static void flushProse(StringBuilder prose, StringBuilder out, Context ctx) {
        if (prose.isEmpty()) {
            return;
        }
        String text = COMMENT.matcher(prose).replaceAll("");
        Matcher code = INLINE_CODE.matcher(text);
        int last = 0;
        while (code.find()) {
            out.append(transform(text.substring(last, code.start()), ctx));
            out.append(code.group());
            last = code.end();
        }
        out.append(transform(text.substring(last), ctx));
        prose.setLength(0);
    }

    static String transform(String text, Context ctx) {
        Matcher m = TOKENS.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            String replacement;
            if (m.group("embed") != null) {
                replacement = wiki(m.group("embed"), true, ctx);
            } else if (m.group("wiki") != null) {
                replacement = wiki(m.group("wiki"), false, ctx);
            } else if (m.group("idest") != null) {
                replacement = markdownImage(m.group("ialt"), m.group("idest"), ctx);
            } else if (m.group("ldest") != null) {
                replacement = markdownLink(m.group(), m.group("ltext"), m.group("ldest"), ctx);
            } else if (m.group("img") != null) {
                replacement = htmlImage(m.group("img"));
            } else if (m.group("auto") != null) {
                ctx.link(m.group("auto"), m.group("auto"));
                replacement = m.group();
            } else {
                String url = trimTrailingPunctuation(m.group("bare"));
                ctx.link(url, url);
                replacement = url;
                // give back what was trimmed
                sb.append(replacement);
                last = m.start() + url.length();
                continue;
            }
            sb.append(replacement);
            last = m.end();
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------- wikilinks

    private static String wiki(String inner, boolean embed, Context ctx) {
        String target = inner;
        String alias = null;
        int pipe = inner.indexOf('|');
        if (pipe >= 0) {
            target = inner.substring(0, pipe);
            // inside tables the pipe is escaped: [[Note\|alias]]
            if (target.endsWith("\\")) {
                target = target.substring(0, target.length() - 1);
            }
            alias = inner.substring(pipe + 1).strip();
            if (alias.isEmpty()) {
                alias = null;
            }
        }
        String file = target;
        String subpath = null;
        int hash = target.indexOf('#');
        if (hash >= 0) {
            file = target.substring(0, hash);
            subpath = target.substring(hash + 1).replace("^", "").strip();
        }
        file = file.strip();
        String name = lastSegment(file);
        String ext = extension(name);

        if (IMAGE_EXTENSIONS.contains(ext)) {
            String label = alias != null && !IMAGE_SIZE.matcher(alias).matches() ? alias : name;
            return "[image: " + label + "]";
        }
        if (!ext.isEmpty() && !ext.equals("md")) {
            return "[attachment: " + name + "]";
        }
        if (file.isEmpty()) {
            // [[#Heading]]: a link inside the same note
            return alias != null ? alias : (subpath == null ? "" : subpath);
        }
        var resolved = ctx.resolver.resolve(file, ctx.source);
        if (resolved.isPresent()) {
            ctx.note(resolved.get());
            return "[[" + inner + "]]";
        }
        String display = stripMd(name);
        if (subpath != null && !subpath.isEmpty()) {
            display = display + " > " + subpath;
        }
        return embed || alias == null ? display : alias;
    }

    // ------------------------------------------------------------------------------------- markdown links

    private static String markdownImage(String alt, String rawDest, Context ctx) {
        String dest = cleanDest(rawDest);
        String label = alt == null ? "" : alt;
        int pipe = label.indexOf('|');
        if (pipe >= 0) {
            label = label.substring(0, pipe);
        }
        label = label.strip();
        if (!EXTERNAL_SCHEME.matcher(dest).matches() && !ANY_SCHEME.matcher(dest).matches()) {
            String path = decode(stripFragment(dest));
            String ext = extension(lastSegment(path));
            if (ext.equals("md") || (ext.isEmpty() && !path.isEmpty())) {
                // ![](Other note.md) embeds a note
                var resolved = ctx.resolver.resolve(path, ctx.source);
                if (resolved.isPresent()) {
                    ctx.note(resolved.get());
                    return "[[" + resolved.get().pathWithoutExtension() + "]]";
                }
                return stripMd(lastSegment(path));
            }
            if (!ext.isEmpty() && !IMAGE_EXTENSIONS.contains(ext)) {
                return "[attachment: " + lastSegment(path) + "]";
            }
        }
        return "[image: " + (label.isEmpty() ? fileNameOf(dest) : label) + "]";
    }

    private static String markdownLink(String whole, String rawText, String rawDest, Context ctx) {
        String dest = cleanDest(rawDest);
        String text = transform(rawText, ctx);
        if (dest.isEmpty() || dest.startsWith("#")) {
            return "[" + text + "](" + rawDest.strip() + ")";
        }
        if (EXTERNAL_SCHEME.matcher(dest).matches()) {
            ctx.link(text.isBlank() ? dest : text, dest);
            return "[" + text + "](" + dest + ")";
        }
        if (dest.regionMatches(true, 0, "mailto:", 0, 7)) {
            return whole;
        }
        if (ANY_SCHEME.matcher(dest).matches()) {
            // obsidian://, file://, app-specific schemes: could point at anything, keep only the text
            return text;
        }
        String path = decode(stripFragment(dest));
        String name = lastSegment(path);
        String ext = extension(name);
        if (IMAGE_EXTENSIONS.contains(ext)) {
            return (text.isBlank() ? "" : text + " ") + "[image: " + name + "]";
        }
        if (!ext.isEmpty() && !ext.equals("md")) {
            return (text.isBlank() ? "" : text + " ") + "[attachment: " + name + "]";
        }
        var resolved = ctx.resolver.resolve(path, ctx.source);
        if (resolved.isPresent()) {
            ctx.note(resolved.get());
            return "[" + text + "](" + rawDest.strip() + ")";
        }
        return text.isBlank() ? stripMd(name) : text;
    }

    private static String htmlImage(String tag) {
        Matcher alt = IMG_ALT.matcher(tag);
        if (alt.find()) {
            String a = unquote(alt.group(1)).strip();
            if (!a.isEmpty()) {
                return "[image: " + a + "]";
            }
        }
        Matcher src = IMG_SRC.matcher(tag);
        return "[image: " + (src.find() ? fileNameOf(unquote(src.group(1))) : "image") + "]";
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static String cleanDest(String raw) {
        String d = raw.strip();
        if (d.startsWith("<") && d.endsWith(">")) {
            d = d.substring(1, d.length() - 1).strip();
        }
        return d;
    }

    private static String stripFragment(String dest) {
        int i = dest.indexOf('#');
        String d = i >= 0 ? dest.substring(0, i) : dest;
        int q = d.indexOf('?');
        return q >= 0 ? d.substring(0, q) : d;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static String fileNameOf(String dest) {
        String name = lastSegment(decode(stripFragment(dest)));
        return name.isEmpty() ? "image" : name;
    }

    private static String lastSegment(String path) {
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripMd(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") || s.startsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String trimTrailingPunctuation(String url) {
        String u = url;
        while (!u.isEmpty()) {
            char c = u.charAt(u.length() - 1);
            if (".,;:!?*_~".indexOf(c) >= 0) {
                u = u.substring(0, u.length() - 1);
            } else if (c == ')' && count(u, '(') < count(u, ')')) {
                u = u.substring(0, u.length() - 1);
            } else {
                break;
            }
        }
        return u;
    }

    private static long count(String s, char c) {
        return s.chars().filter(x -> x == c).count();
    }

    static final class Context {
        final Note source;
        final LinkResolver resolver;
        final Map<String, ExternalLink> links = new LinkedHashMap<>();
        final Map<String, NoteRef> notes = new LinkedHashMap<>();

        Context(Note source, LinkResolver resolver) {
            this.source = source;
            this.resolver = resolver;
        }

        void link(String text, String url) {
            links.putIfAbsent(url, new ExternalLink(text, url));
        }

        void note(Note n) {
            if (!n.id().equals(source.id())) {
                notes.putIfAbsent(n.id(), new NoteRef(n.id(), n.title()));
            }
        }
    }
}
