package eu.rossotto.obsidian.service;

import eu.rossotto.obsidian.config.ConfigLoader;
import eu.rossotto.obsidian.config.ServerConfig;
import eu.rossotto.obsidian.config.VaultConfig;
import eu.rossotto.obsidian.vault.IndexedContent;
import eu.rossotto.obsidian.vault.Note;
import eu.rossotto.obsidian.vault.TagRules;
import eu.rossotto.obsidian.vault.Vault;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Entry point to all vaults. Every public method only ever sees visible notes.
 *
 * <p>The index is refreshed lazily: a call arriving more than {@link #REFRESH_INTERVAL_MS} after the previous
 * scan triggers an incremental rescan (only changed files are re-parsed), so edits made in Obsidian show up
 * almost immediately without a file watcher.
 */
@Startup
@ApplicationScoped
public class NoteRepository {

    private static final Logger LOG = Logger.getLogger(NoteRepository.class);
    static final long REFRESH_INTERVAL_MS = 1_000;

    private final Map<String, Vault> vaults = new LinkedHashMap<>();
    private volatile String configError;
    private volatile long lastRefresh;

    @PostConstruct
    void init() {
        Optional<ConfigLoader.Candidate> found = ConfigLoader.locate();
        if (found.isEmpty()) {
            configError = "No configuration file found. Looked in: " + ConfigLoader.candidates().stream()
                    .map(c -> c.path() + " (" + c.source() + ")").collect(Collectors.joining(", "));
            LOG.error(configError);
            return;
        }
        Path file = found.get().path();
        Path real = ConfigLoader.realPath(file);
        LOG.infof("Using configuration file %s (from %s)%s", file, found.get().source(),
                real.equals(file) ? "" : ", symlink to " + real);
        try {
            ServerConfig config = ConfigLoader.load(file);
            configure(config);
            for (VaultConfig v : config.vaults()) {
                LOG.infof("Vault '%s' at %s (allowed-tags=%s, ignored-tags=%s, show-untagged=%s)",
                        v.name(), v.root(), v.allowedTags(), v.ignoredTags(), v.showUntagged());
            }
        } catch (ConfigLoader.ConfigException e) {
            configError = e.getMessage() + " (configuration file: " + file + ")";
            LOG.error(configError);
            return;
        }
        long start = System.nanoTime();
        refreshNow();
        LOG.infof("Indexed %d visible notes in %d vault(s) in %d ms",
                vaults.values().stream().mapToInt(v -> v.snapshot().visibleNotes().size()).sum(),
                vaults.size(), (System.nanoTime() - start) / 1_000_000);
        // Pre-render in the background so the first full-text search is as fast as the following ones.
        Thread.ofVirtual().name("obsidian-warmup").start(this::warmUp);
    }

    /** Visible for tests. */
    public void configure(ServerConfig config) {
        vaults.clear();
        configError = null;
        for (VaultConfig vc : config.vaults()) {
            vaults.put(vc.name(), new Vault(vc));
        }
        lastRefresh = 0;
    }

    private void warmUp() {
        for (Vault v : vaults.values()) {
            Vault.Snapshot s = v.snapshot();
            s.visibleNotes().parallelStream().forEach(n -> {
                try {
                    s.content(n);
                } catch (IOException | RuntimeException e) {
                    LOG.debugf("Warm-up skipped %s: %s", n.id(), e.getMessage());
                }
            });
        }
    }

    private void ensureReady() {
        if (configError != null) {
            throw new RepositoryException(configError);
        }
        long now = System.currentTimeMillis();
        if (now - lastRefresh > REFRESH_INTERVAL_MS) {
            synchronized (this) {
                if (now - lastRefresh > REFRESH_INTERVAL_MS) {
                    refreshNow();
                }
            }
        }
    }

    private void refreshNow() {
        vaults.values().parallelStream().forEach(Vault::refresh);
        lastRefresh = System.currentTimeMillis();
    }

    // ---------------------------------------------------------------------------------------------- queries

    public record VaultInfo(String name, int notes) {
    }

    public List<VaultInfo> vaults() {
        ensureReady();
        return vaults.values().stream()
                .map(v -> new VaultInfo(v.name(), v.snapshot().visibleNotes().size()))
                .toList();
    }

    /**
     * @param vault optional vault name
     * @param tag   optional tag; nested tags match (tag {@code project} also returns {@code project/alpha})
     */
    public List<Note> list(String vault, String tag) {
        ensureReady();
        String wanted = tag == null || tag.isBlank() ? null : VaultConfig.normalizeTag(tag);
        List<Note> result = new ArrayList<>();
        for (Vault v : selectVaults(vault)) {
            for (Note n : v.snapshot().visibleNotes()) {
                if (wanted == null || n.tags().stream()
                        .anyMatch(t -> TagRules.matches(VaultConfig.normalizeTag(t), wanted))) {
                    result.add(n);
                }
            }
        }
        return result;
    }

    public record Loaded(Note note, IndexedContent content) {
    }

    /** Loads a visible note. Hidden and missing notes produce the same error. */
    public Loaded get(String id) {
        ensureReady();
        String key = id == null ? "" : id.strip();
        int slash = key.indexOf('/');
        Vault vault = slash > 0 ? vaults.get(key.substring(0, slash)) : null;
        if (vault != null) {
            Vault.Snapshot s = vault.snapshot();
            Optional<Note> note = s.visibleByPath(key.substring(slash + 1));
            if (note.isEmpty() && !key.toLowerCase().endsWith(".md")) {
                note = s.visibleByPath(key.substring(slash + 1) + ".md");
            }
            if (note.isPresent()) {
                try {
                    return new Loaded(note.get(), s.content(note.get()));
                } catch (IOException e) {
                    LOG.debugf("Cannot read %s: %s", key, e.getMessage());
                }
            }
        }
        throw new RepositoryException("Note not found: " + id);
    }

    public List<SearchEngine.Hit> search(String query, String vault, int limit) {
        ensureReady();
        List<SearchEngine.Candidate> candidates = new ArrayList<>();
        for (Vault v : selectVaults(vault)) {
            Vault.Snapshot s = v.snapshot();
            // Rendering is the expensive part on a cold cache: spread it over all cores.
            s.visibleNotes().parallelStream()
                    .map(n -> {
                        try {
                            return new SearchEngine.Candidate(n, s.content(n));
                        } catch (IOException e) {
                            LOG.debugf("Search skipped %s: %s", n.id(), e.getMessage());
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .forEachOrdered(candidates::add);
        }
        return SearchEngine.search(query, candidates, limit);
    }

    private List<Vault> selectVaults(String name) {
        if (name == null || name.isBlank()) {
            return List.copyOf(vaults.values());
        }
        Vault v = vaults.get(name.strip());
        if (v == null) {
            throw new RepositoryException("Unknown vault: " + name + ". Available: " + String.join(", ", vaults.keySet()));
        }
        return List.of(v);
    }

    public static final class RepositoryException extends RuntimeException {
        public RepositoryException(String message) {
            super(message);
        }
    }
}
