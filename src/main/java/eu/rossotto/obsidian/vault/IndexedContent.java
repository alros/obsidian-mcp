package eu.rossotto.obsidian.vault;

import java.util.Locale;

/** Rendered note plus a lower-cased copy used for full-text search. */
public record IndexedContent(RenderedNote rendered, String lowerContent) {

    public static IndexedContent of(RenderedNote rendered) {
        return new IndexedContent(rendered, rendered.content().toLowerCase(Locale.ROOT));
    }
}
