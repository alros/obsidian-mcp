package eu.rossotto.obsidian.mcp;

import eu.rossotto.obsidian.service.NoteRepository;
import eu.rossotto.obsidian.service.NoteRepository.RepositoryException;
import eu.rossotto.obsidian.service.SearchEngine;
import eu.rossotto.obsidian.vault.Note;
import eu.rossotto.obsidian.vault.RenderedNote;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;

import java.util.List;
import java.util.function.Supplier;

/** MCP tools exposed to Claude, Copilot and any other MCP client. All of them are read-only. */
public class ObsidianTools {

    private static final int DEFAULT_LIST_LIMIT = 500;
    private static final int DEFAULT_SEARCH_LIMIT = 20;

    @Inject
    NoteRepository repository;

    // --------------------------------------------------------------------------------------------- DTOs

    @RegisterForReflection
    public record VaultSummary(String name, int notes) {
    }

    @RegisterForReflection
    public record VaultList(List<VaultSummary> vaults) {
    }

    @RegisterForReflection
    public record NoteSummary(String id, String title, List<String> tags) {
    }

    @RegisterForReflection
    public record NoteList(int total, int offset, int returned, boolean truncated, List<NoteSummary> notes) {
    }

    @RegisterForReflection
    public record NoteContent(String id, String vault, String path, String title, List<String> tags,
                              String content, List<RenderedNote.ExternalLink> links,
                              List<RenderedNote.NoteRef> linkedNotes) {
    }

    @RegisterForReflection
    public record SearchResult(String id, String title, List<String> tags, double score, List<String> snippets) {
    }

    @RegisterForReflection
    public record SearchResults(String query, int returned, List<SearchResult> results) {
    }

    // -------------------------------------------------------------------------------------------- tools

    @Tool(name = "list_vaults",
            description = "Lists the configured Obsidian vaults with the number of notes available in each.",
            annotations = @Tool.Annotations(title = "List vaults", readOnlyHint = true, idempotentHint = true,
                    openWorldHint = false, destructiveHint = false))
    public VaultList listVaults() {
        return call(() -> new VaultList(repository.vaults().stream()
                .map(v -> new VaultSummary(v.name(), v.notes()))
                .toList()));
    }

    @Tool(name = "list_notes",
            description = "Lists notes from the Obsidian vaults: id, title and tags of each note. "
                    + "Use the id with get_note to read a note. Nested tags match their parent: "
                    + "tag 'project' also returns notes tagged 'project/alpha'.",
            annotations = @Tool.Annotations(title = "List notes", readOnlyHint = true, idempotentHint = true,
                    openWorldHint = false, destructiveHint = false))
    public NoteList listNotes(
            @ToolArg(description = "Only list notes of this vault (see list_vaults). Omit for all vaults.",
                    required = false) String vault,
            @ToolArg(description = "Only list notes carrying this tag (or one of its nested tags). Omit for all tags.",
                    required = false) String tag,
            @ToolArg(description = "Number of notes to skip, for paging.", required = false, defaultValue = "0")
            int offset,
            @ToolArg(description = "Maximum number of notes to return.", required = false,
                    defaultValue = "" + DEFAULT_LIST_LIMIT) int limit) {
        return call(() -> {
            List<Note> all = repository.list(vault, tag);
            int from = Math.clamp(offset, 0, all.size());
            int to = Math.clamp((long) from + Math.max(1, limit), from, all.size());
            List<NoteSummary> page = all.subList(from, to).stream()
                    .map(n -> new NoteSummary(n.id(), n.title(), n.tags()))
                    .toList();
            return new NoteList(all.size(), from, page.size(), to < all.size(), page);
        });
    }

    @Tool(name = "get_note",
            description = "Returns the markdown content of a note. Images are replaced by [image: name] placeholders "
                    + "and attachments by [attachment: name]. 'links' lists the external web links found in the note, "
                    + "which you may decide to follow. 'linkedNotes' lists the notes it links to, readable with get_note.",
            annotations = @Tool.Annotations(title = "Read note", readOnlyHint = true, idempotentHint = true,
                    openWorldHint = false, destructiveHint = false))
    public NoteContent getNote(
            @ToolArg(description = "Note id as returned by list_notes or search_notes, e.g. 'work/projects/Plan.md'.")
            String id) {
        return call(() -> {
            NoteRepository.Loaded loaded = repository.get(id);
            Note n = loaded.note();
            RenderedNote r = loaded.content().rendered();
            return new NoteContent(n.id(), n.vault(), n.path(), n.title(), n.tags(), r.content(), r.links(),
                    r.linkedNotes());
        });
    }

    @Tool(name = "search_notes",
            description = "Full-text search across note titles, tags, paths and content (case-insensitive). "
                    + "All terms must match; use double quotes for exact phrases, e.g. '\"release plan\" quarkus'. "
                    + "Returns the best matching notes with snippets.",
            annotations = @Tool.Annotations(title = "Search notes", readOnlyHint = true, idempotentHint = true,
                    openWorldHint = false, destructiveHint = false))
    public SearchResults searchNotes(
            @ToolArg(description = "Search terms.") String query,
            @ToolArg(description = "Only search this vault. Omit for all vaults.", required = false) String vault,
            @ToolArg(description = "Maximum number of results.", required = false,
                    defaultValue = "" + DEFAULT_SEARCH_LIMIT) int limit) {
        return call(() -> {
            if (query == null || query.isBlank()) {
                throw new RepositoryException("query must not be empty");
            }
            List<SearchEngine.Hit> hits = repository.search(query, vault, Math.clamp(limit, 1, 200));
            return new SearchResults(query, hits.size(), hits.stream()
                    .map(h -> new SearchResult(h.note().id(), h.note().title(), h.note().tags(), h.score(), h.snippets()))
                    .toList());
        });
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (RepositoryException e) {
            throw new ToolCallException(e.getMessage());
        }
    }
}
