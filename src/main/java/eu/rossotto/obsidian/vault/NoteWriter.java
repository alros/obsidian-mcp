package eu.rossotto.obsidian.vault;

import eu.rossotto.obsidian.config.VaultConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Creates new notes in a vault's inbox folder. It never opens an existing file for writing: every file is
 * created with {@link StandardOpenOption#CREATE_NEW}, so even concurrent calls can't overwrite a note.
 */
public final class NoteWriter {

    /** Longest title, in UTF-8 bytes, leaving room for {@code -NNNN.md} within the usual 255-byte file name limit. */
    static final int MAX_TITLE_BYTES = 200;
    static final int MAX_ATTEMPTS = 10_000;

    /** Characters Obsidian or the file system don't accept in a note name. */
    private static final String FORBIDDEN = "\\/:*?\"<>|#^[]";
    private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private NoteWriter() {
    }

    /**
     * @param file    absolute path of the new file
     * @param relPath path inside the vault, '/'-separated
     * @param title   final title, including any {@code -N} suffix
     * @param tags    tags written to the frontmatter
     */
    public record Created(Path file, String relPath, String title, List<String> tags, LocalDateTime created) {

        /** The {@code created} property as written in the note. */
        public String createdText() {
            return CREATED.format(created);
        }
    }

    public static Created create(VaultConfig vault, String title, String body, List<String> tags, Clock clock)
            throws IOException {
        String base = sanitizeTitle(title);
        List<String> allTags = mergeTags(vault.autoTag(), tags);
        LocalDateTime created = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        byte[] content = render(created, allTags, body).getBytes(StandardCharsets.UTF_8);

        Path root = vault.root();
        Path inbox = vault.inboxRoot();
        if (!inbox.startsWith(root)) {
            throw new IOException("inbox-folder " + inbox + " is outside the vault " + root);
        }
        Files.createDirectories(inbox);
        Set<String> taken = existingNames(inbox);

        for (int n = 0; n < MAX_ATTEMPTS; n++) {
            String candidate = n == 0 ? base : base + "-" + n;
            String fileName = candidate + ".md";
            if (!taken.add(key(fileName))) {
                continue;
            }
            Path file = inbox.resolve(fileName);
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                out.write(content);
            } catch (FileAlreadyExistsException e) {
                continue; // created in the meantime, or a name the file system considers equal
            } catch (IOException e) {
                Files.deleteIfExists(file);
                throw e;
            }
            String rel = root.relativize(file).toString().replace('\\', '/');
            return new Created(file, rel, candidate, allTags, created);
        }
        throw new IOException("Too many notes named '" + base + "' in " + inbox);
    }

    /**
     * Turns a free-form title into a safe file name: forbidden characters become {@code -}, whitespace collapses,
     * leading dots go (no hidden files, no {@code ..}) and a trailing {@code .md} is dropped.
     *
     * @throws IllegalArgumentException if nothing usable is left
     */
    public static String sanitizeTitle(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("title must not be empty");
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp) || Character.isWhitespace(cp)) {
                sb.append(' ');
            } else if (FORBIDDEN.indexOf(cp) >= 0) {
                sb.append('-');
            } else {
                sb.appendCodePoint(cp);
            }
        });
        String t = sb.toString().replaceAll(" {2,}", " ").strip();
        if (t.toLowerCase(Locale.ROOT).endsWith(".md")) {
            t = t.substring(0, t.length() - 3).strip();
        }
        t = t.replaceAll("^[. ]+", "");
        t = truncate(t, MAX_TITLE_BYTES);
        // Trailing dots and spaces are dropped by Windows and some sync services.
        t = t.replaceAll("[. ]+$", "");
        if (t.isEmpty()) {
            throw new IllegalArgumentException("title '" + raw + "' has no usable characters");
        }
        return t;
    }

    /** The auto tag first, then the given tags; duplicates (ignoring case) keep their first spelling. */
    public static List<String> mergeTags(String autoTag, List<String> tags) {
        Map<String, String> merged = new LinkedHashMap<>();
        List<String> all = new ArrayList<>();
        if (autoTag != null) {
            all.add(autoTag);
        }
        if (tags != null) {
            all.addAll(tags);
        }
        for (String raw : all) {
            String tag = VaultConfig.validateTag(raw);
            merged.putIfAbsent(VaultConfig.normalizeTag(tag), tag);
        }
        return List.copyOf(merged.values());
    }

    static String render(LocalDateTime created, List<String> tags, String body) {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("created: ").append(CREATED.format(created)).append('\n');
        if (!tags.isEmpty()) {
            sb.append("tags:\n");
            // validateTag guarantees plain YAML scalars: no quoting needed
            tags.forEach(t -> sb.append("  - ").append(t).append('\n'));
        }
        sb.append("---\n");
        if (body != null) {
            sb.append(body);
        }
        return sb.toString();
    }

    private static Set<String> existingNames(Path dir) throws IOException {
        Set<String> names = new HashSet<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.forEach(p -> names.add(key(p.getFileName().toString())));
        }
        return names;
    }

    /** Obsidian resolves links case-insensitively, and macOS stores names decomposed (NFD). */
    private static String key(String fileName) {
        return Normalizer.normalize(fileName, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static String truncate(String s, int maxBytes) {
        if (s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + len > maxBytes) {
                break;
            }
            sb.appendCodePoint(cp);
            bytes += len;
            i += Character.charCount(cp);
        }
        return sb.toString().strip();
    }
}
