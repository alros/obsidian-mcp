package eu.rossotto.obsidian.service;

import eu.rossotto.obsidian.Fixtures;
import eu.rossotto.obsidian.vault.Note;
import eu.rossotto.obsidian.vault.RenderedNote;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteRepositoryTest {

    static NoteRepository repo;
    static String alpha;
    static RenderedNote alphaRendered;

    @BeforeAll
    static void setUp() {
        repo = Fixtures.repository();
        NoteRepository.Loaded loaded = repo.get("work/Projects/Alpha.md");
        alphaRendered = loaded.content().rendered();
        alpha = alphaRendered.content();
    }

    private static List<String> ids(List<Note> notes) {
        return notes.stream().map(Note::id).sorted().toList();
    }

    // ------------------------------------------------------------------------------------- visibility

    @Test
    void listsOnlyVisibleNotes() {
        assertEquals(List.of(
                "personal/Loose.md",
                "personal/Movies.md",
                "work/Howto/Git.md",
                "work/Projects/Alpha.md",
                "work/Projects/Beta.md"), ids(repo.list(null, null)));
    }

    @Test
    void filtersByVaultAndNestedTag() {
        assertEquals(List.of("work/Projects/Alpha.md", "work/Projects/Beta.md"), ids(repo.list("work", "project")));
        assertEquals(List.of("work/Projects/Alpha.md"), ids(repo.list(null, "#project/alpha")));
        assertEquals(List.of("personal/Movies.md"), ids(repo.list("personal", "cinema")));
    }

    @Test
    void unknownVaultIsAnError() {
        assertThrows(NoteRepository.RepositoryException.class, () -> repo.list("nope", null));
    }

    @Test
    void titlesAndTags() {
        Note a = repo.get("work/Projects/Alpha.md").note();
        assertEquals("Project Alpha", a.title());
        assertEquals(List.of("project/alpha", "Status/Active"), a.tags());
        assertEquals("Beta", repo.get("work/Projects/Beta").note().title());
    }

    @Test
    void hiddenAndMissingNotesAreIndistinguishable() {
        for (String id : List.of("work/Secret.md", "work/Untagged.md", "work/Other.md", "work/Projects/Plain.md",
                "work/.obsidian/workspace.md", "personal/Diary/2026-01-01.md", "work/Nope.md",
                "work/../personal/Movies.md", "../../etc/passwd", "")) {
            var e = assertThrows(NoteRepository.RepositoryException.class, () -> repo.get(id), id);
            assertEquals("Note not found: " + id, e.getMessage());
        }
    }

    @Test
    void vaultCounts() {
        assertEquals(List.of(new NoteRepository.VaultInfo("work", 3), new NoteRepository.VaultInfo("personal", 2)),
                repo.vaults());
    }

    // -------------------------------------------------------------------------------------- rendering

    @Test
    void frontmatterIsStripped() {
        assertTrue(alpha.startsWith("# Alpha"));
        assertEquals("this is the body of the note", repo.get("personal/Movies.md").content().rendered().content());
    }

    @Test
    void imagesBecomePlaceholders() {
        assertTrue(alpha.contains("Architecture: [image: diagram.png] and [image: photo.jpg] and [image: Team photo]."));
        assertTrue(alpha.contains("Remote image: [image: banner.webp]"));
        assertTrue(alpha.contains("HTML image: [image: Company logo]"));
        assertFalse(alpha.contains("cdn.example.com"));
        assertFalse(alpha.contains("img/logo.png"));
    }

    @Test
    void attachmentsBecomePlaceholders() {
        assertTrue(alpha.contains("Spec: [attachment: report.pdf] and [attachment: recording.m4a]."));
    }

    @Test
    void linksToVisibleNotesAreKept() {
        assertTrue(alpha.contains("See [[Beta]] and [[Beta#Goals|the goals]] and [beta md](Beta.md)."));
        assertTrue(alpha.contains("| row | [[Beta\\|beta alias]] |"));
        assertEquals(List.of(new RenderedNote.NoteRef("work/Projects/Beta.md", "Beta")), alphaRendered.linkedNotes());
    }

    @Test
    void linksToHiddenNotesBecomePlainText() {
        assertTrue(alpha.contains("Hidden: Secret, aliased my alias, embed Secret, heading Secret > Plan."), alpha);
        assertTrue(alpha.contains("Missing: Does not exist. Markdown hidden: secret notes."));
        assertTrue(alpha.contains("Deep link: open in obsidian\n"));
        assertFalse(alpha.contains("obsidian://"));
    }

    @Test
    void externalLinksAreKeptAndCollected() {
        assertTrue(alpha.contains("[Quarkus](https://quarkus.io/guides/)"));
        assertTrue(alpha.contains("Badge: [[image: build]](https://github.com/acme/alpha)"));
        List<String> urls = alphaRendered.links().stream().map(RenderedNote.ExternalLink::url).toList();
        assertEquals(List.of(
                "https://quarkus.io/guides/",
                "https://example.com/path/page.html",
                "https://en.wikipedia.org/wiki/Java_(programming_language)",
                "https://autolink.example.org",
                "https://github.com/acme/alpha"), urls);
        assertEquals("Quarkus", alphaRendered.links().getFirst().text());
    }

    @Test
    void commentsAreRemoved() {
        assertFalse(alpha.contains("leak.example.com"));
        assertFalse(alpha.contains("private comment"));
    }

    @Test
    void codeIsUntouched() {
        assertTrue(alpha.contains("`[[Secret]] ![[code.png]]`"));
        assertTrue(alpha.contains("// ![[inside-code.png]] https://code.example.com [[Secret]]"));
    }

    @Test
    void bodyTagsAndSelfLinksStay() {
        assertTrue(alpha.contains("Inline tag stays: #idea"));
        assertTrue(alpha.contains("Same note: Alpha"));
        assertTrue(alpha.contains("[mail me](mailto:someone@example.com)"));
    }

    // ----------------------------------------------------------------------------------------- search

    private static List<String> searchIds(String q) {
        return repo.search(q, null, 20).stream().map(h -> h.note().id()).toList();
    }

    @Test
    void searchNeverReturnsHiddenNotes() {
        // "quarkus" also appears in Secret.md and Untagged.md
        assertEquals(List.of("work/Projects/Alpha.md", "work/Projects/Beta.md"),
                searchIds("quarkus").stream().sorted().toList());
        assertTrue(searchIds("leak.example.com").isEmpty());
        assertTrue(searchIds("\"top secret\"").isEmpty());
        assertTrue(searchIds("diary").isEmpty());
    }

    @Test
    void searchRanksAndSnippets() {
        List<SearchEngine.Hit> hits = repo.search("cinema", null, 20);
        assertEquals("personal/Movies.md", hits.getFirst().note().id()); // tag match ranks first
        assertEquals("personal/Loose.md", hits.get(1).note().id());
        assertTrue(hits.get(1).snippets().getFirst().contains("cinema"));
    }

    @Test
    void searchRequiresAllTermsAndSupportsPhrases() {
        assertEquals(List.of("work/Projects/Beta.md"), searchIds("\"native release\" quarkus"));
        assertTrue(searchIds("quarkus banana").isEmpty());
    }
}
