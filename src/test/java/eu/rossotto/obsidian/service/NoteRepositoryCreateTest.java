package eu.rossotto.obsidian.service;

import eu.rossotto.obsidian.config.ServerConfig;
import eu.rossotto.obsidian.config.VaultConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteRepositoryCreateTest {

    @TempDir
    Path tmp;
    NoteRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        Path open = Files.createDirectories(tmp.resolve("open"));
        Path strict = Files.createDirectories(tmp.resolve("strict"));
        Path readonly = Files.createDirectories(tmp.resolve("readonly"));
        repo = new NoteRepository();
        repo.clock = Clock.fixed(Instant.parse("2026-11-17T10:42:00Z"), ZoneOffset.UTC);
        repo.configure(new ServerConfig(List.of(
                new VaultConfig("open", open.toString(), List.of(), List.of(), true, true, null, "ai"),
                new VaultConfig("strict", strict.toString(), List.of("project"), List.of(), false, true,
                        "Inbox", null),
                new VaultConfig("readonly", readonly.toString(), List.of(), List.of(), true))));
    }

    @Test
    void createdNotesAreImmediatelyListedAndReadable() {
        var c = repo.create("open", "Idea", "Some *text*", List.of("thinking"));
        assertEquals("open/AI-input/Idea.md", c.id());
        assertTrue(c.visible());
        assertEquals(List.of("ai", "thinking"), c.created().tags());
        assertEquals("Some *text*", repo.get(c.id()).content().rendered().content());
        assertEquals(List.of("open/AI-input/Idea.md"), repo.list("open", "ai").stream().map(n -> n.id()).toList());

        assertEquals("open/AI-input/Idea-1.md", repo.create("open", "Idea", "again", null).id());
    }

    @Test
    void notesHiddenByTagRulesAreCreatedButNotVisible() {
        var hidden = repo.create("strict", "Loose thought", "x", List.of("random"));
        assertFalse(hidden.visible());
        assertTrue(Files.isRegularFile(tmp.resolve("strict/Inbox/Loose thought.md")));
        assertThrows(NoteRepository.RepositoryException.class, () -> repo.get(hidden.id()));

        var shown = repo.create("strict", "Plan", "x", List.of("project/new"));
        assertTrue(shown.visible());
        assertEquals("strict/Inbox/Plan.md", shown.id());
    }

    @Test
    void readOnlyVaultsRefuse() {
        var e = assertThrows(NoteRepository.RepositoryException.class,
                () -> repo.create("readonly", "x", "y", List.of()));
        assertTrue(e.getMessage().contains("allow-create"), e.getMessage());
        assertTrue(Files.notExists(tmp.resolve("readonly/AI-input")));
    }

    @Test
    void vaultIsRequiredAndMustExist() {
        assertThrows(NoteRepository.RepositoryException.class, () -> repo.create(null, "x", "y", null));
        assertThrows(NoteRepository.RepositoryException.class, () -> repo.create(" ", "x", "y", null));
        assertThrows(NoteRepository.RepositoryException.class, () -> repo.create("nope", "x", "y", null));
    }

    @Test
    void invalidInputBecomesAToolError() {
        assertThrows(NoteRepository.RepositoryException.class, () -> repo.create("open", "..", "y", null));
        assertThrows(NoteRepository.RepositoryException.class,
                () -> repo.create("open", "x", "y", List.of("has space")));
    }
}
