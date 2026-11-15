package eu.rossotto.obsidian.vault;

import eu.rossotto.obsidian.config.VaultConfig;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory index of one vault. {@link #refresh()} rescans the tree and re-parses only the files whose
 * size or modification time changed, then atomically swaps in a new immutable {@link Snapshot}.
 */
public final class Vault {

    private static final Logger LOG = Logger.getLogger(Vault.class);

    private final VaultConfig config;
    private final Path root;
    private final TagRules rules;
    private volatile Snapshot snapshot = new Snapshot(Map.of());

    public Vault(VaultConfig config) {
        this.config = config;
        this.root = config.root();
        this.rules = TagRules.of(config);
    }

    public String name() {
        return config.name();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** @return true if anything changed */
    public synchronized boolean refresh() {
        Map<String, Note> previous = snapshot.byPath;
        Map<String, Note> next = new HashMap<>(Math.max(16, previous.size() * 2));
        boolean[] changed = {false};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Skips .obsidian, .trash, .git and any other hidden folder.
                    if (!dir.equals(root) && dir.getFileName().toString().startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (!attrs.isRegularFile() || fileName.startsWith(".")
                            || !fileName.toLowerCase(Locale.ROOT).endsWith(".md")) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    long modified = attrs.lastModifiedTime().toMillis();
                    long size = attrs.size();
                    Note old = previous.get(rel);
                    if (old != null && old.modified() == modified && old.size() == size) {
                        next.put(rel, old);
                    } else {
                        Note parsed = parse(file, rel, modified, size);
                        if (parsed != null) {
                            next.put(rel, parsed);
                        }
                        changed[0] = true;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    LOG.debugf("Skipping unreadable %s: %s", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.errorf("Cannot scan vault '%s' at %s: %s", name(), root, e.getMessage());
            return false;
        }
        if (!changed[0] && next.size() == previous.size()) {
            return false;
        }
        snapshot = new Snapshot(next);
        return true;
    }

    private Note parse(Path file, String rel, long modified, long size) {
        try {
            Frontmatter fm = Frontmatter.parse(readText(file));
            String basename = file.getFileName().toString();
            basename = basename.substring(0, basename.length() - 3);
            return new Note(name() + "/" + rel, name(), rel, fm.title().orElse(basename), fm.tags(),
                    file, modified, size, rules.isVisible(fm.tags()));
        } catch (IOException e) {
            LOG.debugf("Skipping unreadable %s: %s", file, e.getMessage());
            return null;
        }
    }

    static String readText(Path file) throws IOException {
        // Lenient decoding: malformed bytes become U+FFFD instead of failing the whole note.
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** Immutable view of the vault at one point in time. */
    public static final class Snapshot implements LinkResolver {

        private final Map<String, Note> byPath;
        private final List<Note> visible;
        private final Map<String, List<Note>> byBasename = new HashMap<>();
        private final Map<String, Note> byLowerPath = new HashMap<>();
        /** Rendering depends on the visibility of linked notes, so the cache lives and dies with the snapshot. */
        final Map<String, IndexedContent> content = new ConcurrentHashMap<>();

        Snapshot(Map<String, Note> byPath) {
            this.byPath = Map.copyOf(byPath);
            List<Note> v = new ArrayList<>();
            for (Note n : byPath.values()) {
                if (n.visible()) {
                    v.add(n);
                }
                byBasename.computeIfAbsent(n.basename().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(n);
                byLowerPath.put(n.pathWithoutExtension().toLowerCase(Locale.ROOT), n);
            }
            v.sort(Comparator.comparing(Note::path, String.CASE_INSENSITIVE_ORDER));
            this.visible = List.copyOf(v);
        }

        /**
         * Rendered content of a visible note, cached for the lifetime of this snapshot.
         *
         * @throws IOException if the file cannot be read (e.g. deleted since the last scan)
         */
        public IndexedContent content(Note note) throws IOException {
            if (!note.visible()) {
                throw new IllegalArgumentException("Note is not visible");
            }
            IndexedContent cached = content.get(note.id());
            if (cached != null) {
                return cached;
            }
            String body = Frontmatter.parse(readText(note.file())).body();
            IndexedContent fresh = IndexedContent.of(NoteRenderer.render(note, body, this));
            content.put(note.id(), fresh);
            return fresh;
        }

        public List<Note> visibleNotes() {
            return visible;
        }

        public Optional<Note> visibleByPath(String relPath) {
            Note n = byPath.get(relPath);
            return n != null && n.visible() ? Optional.of(n) : Optional.empty();
        }

        /**
         * Resolves a link target the way Obsidian does (across all notes, hidden ones included, so a hidden note
         * never "steals" nor "leaks into" a link meant for another one) and returns it only if visible.
         */
        @Override
        public Optional<Note> resolve(String target, Note source) {
            Note n = resolveAny(target, source);
            return n != null && n.visible() ? Optional.of(n) : Optional.empty();
        }

        private Note resolveAny(String rawTarget, Note source) {
            String target = rawTarget.strip().replace('\\', '/');
            if (target.toLowerCase(Locale.ROOT).endsWith(".md")) {
                target = target.substring(0, target.length() - 3);
            }
            if (target.isEmpty()) {
                return null;
            }
            String lower = target.toLowerCase(Locale.ROOT);
            if (lower.contains("/")) {
                if (lower.startsWith("/")) {
                    return byLowerPath.get(lower.substring(1));
                }
                // relative to the source folder (markdown links), then from the vault root, then by suffix
                String relative = normalizePath(source.folder().toLowerCase(Locale.ROOT), lower);
                if (relative != null && byLowerPath.containsKey(relative)) {
                    return byLowerPath.get(relative);
                }
                if (byLowerPath.containsKey(lower)) {
                    return byLowerPath.get(lower);
                }
                String suffix = "/" + lower;
                return byLowerPath.entrySet().stream()
                        .filter(e -> e.getKey().endsWith(suffix))
                        .map(Map.Entry::getValue)
                        .min(Comparator.comparingInt(n -> n.path().length()))
                        .orElse(null);
            }
            List<Note> candidates = byBasename.get(lower);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            if (candidates.size() == 1) {
                return candidates.getFirst();
            }
            return candidates.stream()
                    .filter(n -> n.folder().equals(source.folder()))
                    .findFirst()
                    .orElseGet(() -> candidates.stream()
                            .min(Comparator.<Note>comparingInt(n -> n.path().length()).thenComparing(Note::path))
                            .orElseThrow());
        }

        private static String normalizePath(String folder, String rel) {
            List<String> parts = new ArrayList<>();
            if (!folder.isEmpty()) {
                parts.addAll(List.of(folder.split("/")));
            }
            for (String p : rel.split("/")) {
                switch (p) {
                    case "", "." -> { }
                    case ".." -> {
                        if (parts.isEmpty()) {
                            return null;
                        }
                        parts.removeLast();
                    }
                    default -> parts.add(p);
                }
            }
            return String.join("/", parts);
        }

        public Collection<Note> allNotes() {
            return byPath.values();
        }
    }
}
