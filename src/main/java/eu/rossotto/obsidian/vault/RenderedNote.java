package eu.rossotto.obsidian.vault;

import java.util.List;

/**
 * Note content after sanitization.
 *
 * @param content     markdown body with frontmatter removed, images replaced by placeholders and links to
 *                    invisible notes flattened to plain text
 * @param links       external (internet) links found in the note
 * @param linkedNotes visible notes this note links to or embeds
 */
public record RenderedNote(String content, List<ExternalLink> links, List<NoteRef> linkedNotes) {

    public record ExternalLink(String text, String url) {
    }

    public record NoteRef(String id, String title) {
    }
}
