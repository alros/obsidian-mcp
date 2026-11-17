package eu.rossotto.obsidian.vault;

import eu.rossotto.obsidian.config.VaultConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteWriterTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T10:42:00Z"), ZoneOffset.UTC);

    @TempDir
    Path vault;

    private VaultConfig config(String inbox, String autoTag) {
        return new VaultConfig("v", vault.toString(), null, null, null, true, inbox, autoTag);
    }

    private NoteWriter.Created create(String title) throws Exception {
        return NoteWriter.create(config(null, null), title, "body", List.of(), CLOCK);
    }

    @Test
    void writesFrontmatterAndBodyAsIs() throws Exception {
        String body = "# Heading\n\nSome *markdown*\r\nwith CRLF and no trailing newline";
        NoteWriter.Created c = NoteWriter.create(config(null, "ai-generated"), "Meeting notes", body,
                List.of("project/alpha", "#idea"), CLOCK);

        assertEquals(vault.resolve("AI-input/Meeting notes.md"), c.file());
        assertEquals("AI-input/Meeting notes.md", c.relPath());
        assertEquals("Meeting notes", c.title());
        assertEquals(List.of("ai-generated", "project/alpha", "idea"), c.tags());
        assertEquals("""
                ---
                created: 2026-11-17T10:42:00
                tags:
                  - ai-generated
                  - project/alpha
                  - idea
                ---
                """ + body, Files.readString(c.file()));

        Frontmatter fm = Frontmatter.parse(Files.readString(c.file()));
        assertEquals(c.tags(), fm.tags());
        assertEquals(body, fm.body());
    }

    @Test
    void noTagsMeansNoTagsProperty() throws Exception {
        NoteWriter.Created c = NoteWriter.create(config(null, null), "Plain", "", null, CLOCK);
        assertEquals("---\ncreated: 2026-11-17T10:42:00\n---\n", Files.readString(c.file()));
    }

    @Test
    void clashingTitlesGetAProgressiveNumber() throws Exception {
        assertEquals("some title", create("some title").title());
        assertEquals("some title-1", create("some title").title());
        assertEquals("some title-2", create("some title").title());
        assertTrue(Files.isRegularFile(vault.resolve("AI-input/some title-2.md")));
    }

    @Test
    void clashesIgnoreCaseAndUnicodeForm() throws Exception {
        Files.createDirectories(vault.resolve("AI-input"));
        Files.writeString(vault.resolve("AI-input/Café.md"), "existing");
        String decomposed = "Café";
        NoteWriter.Created c = create(decomposed.toUpperCase());
        assertEquals("CAFÉ-1", c.title());
        assertEquals("existing", Files.readString(vault.resolve("AI-input/Café.md")), "never overwritten");
    }

    @Test
    void existingNotesAreNeverTouched() throws Exception {
        Path inbox = Files.createDirectories(vault.resolve("AI-input"));
        Files.writeString(inbox.resolve("Todo.md"), "mine");
        Files.writeString(inbox.resolve("Todo-1.md"), "mine too");
        assertEquals("Todo-2", create("Todo").title());
        assertEquals("mine", Files.readString(inbox.resolve("Todo.md")));
        assertEquals("mine too", Files.readString(inbox.resolve("Todo-1.md")));
    }

    @Test
    void concurrentCreatesNeverCollide() throws Exception {
        var titles = ConcurrentHashMap.<String>newKeySet();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 40).mapToObj(i -> pool.submit(() -> titles.add(create("Race").title())))
                    .toList();
            for (var f : futures) {
                f.get();
            }
        }
        assertEquals(40, titles.size());
        try (var files = Files.list(vault.resolve("AI-input"))) {
            assertEquals(40, files.count());
        }
    }

    @Test
    void createsNestedInboxFolder() throws Exception {
        NoteWriter.Created c = NoteWriter.create(config("Inbox/From AI/", null), "x", "", List.of(), CLOCK);
        assertEquals("Inbox/From AI/x.md", c.relPath());
    }

    @Test
    void sanitizesTitles() {
        assertEquals("Meeting- notes - 2026-11-17", NoteWriter.sanitizeTitle("Meeting: notes / 2026-11-17"));
        assertEquals("a-b-c-d-e-f-g-h-i-j-k-l-m-n", NoteWriter.sanitizeTitle("a\\b/c:d*e?f\"g<h>i|j#k^l[m]n"));
        assertEquals("tabs and new lines", NoteWriter.sanitizeTitle("  tabs\tand\n\nnew   lines  "));
        assertEquals("hidden", NoteWriter.sanitizeTitle(".hidden"));
        assertEquals("-..-etc-passwd", NoteWriter.sanitizeTitle("../../etc/passwd"), "can't escape the folder");
        assertEquals("Notes", NoteWriter.sanitizeTitle("Notes.md"));
        assertEquals("ends with dots", NoteWriter.sanitizeTitle("ends with dots..."));
        for (String bad : new String[]{"", "   ", "..", ".", ".md", "\n"}) {
            assertThrows(IllegalArgumentException.class, () -> NoteWriter.sanitizeTitle(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> NoteWriter.sanitizeTitle(null));
    }

    @Test
    void longTitlesAreTruncatedOnCharacterBoundaries() {
        String t = NoteWriter.sanitizeTitle("é".repeat(300));
        assertEquals(NoteWriter.MAX_TITLE_BYTES / 2, t.length());
        String emoji = NoteWriter.sanitizeTitle("a" + "😀".repeat(100));
        assertTrue(emoji.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= NoteWriter.MAX_TITLE_BYTES);
        assertTrue(Character.isLetter(emoji.charAt(0)) && !Character.isHighSurrogate(emoji.charAt(emoji.length() - 1)));
    }

    @Test
    void mergesAndValidatesTags() {
        assertEquals(List.of("AI", "project/alpha"),
                NoteWriter.mergeTags("AI", List.of("#ai", "project/alpha", "Project/Alpha", "##ai")));
        assertEquals(List.of("città", "v2"), NoteWriter.mergeTags(null, List.of("città", "v2")));
        for (String bad : List.of("two words", "a,b", "", "#", "1984", "/x", "x/", "a//b", "a:b", "\"q\"")) {
            assertThrows(IllegalArgumentException.class, () -> NoteWriter.mergeTags(null, List.of(bad)), bad);
        }
    }

    @Test
    void invalidTagsWriteNothing() {
        assertThrows(IllegalArgumentException.class,
                () -> NoteWriter.create(config(null, null), "x", "", List.of("bad tag"), CLOCK));
        assertTrue(Files.notExists(vault.resolve("AI-input")));
    }
}
