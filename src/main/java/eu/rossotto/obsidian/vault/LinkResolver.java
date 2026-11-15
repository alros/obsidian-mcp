package eu.rossotto.obsidian.vault;

import java.util.Optional;

/** Resolves an internal link target to a <em>visible</em> note. Hidden and missing notes look identical. */
@FunctionalInterface
public interface LinkResolver {

    Optional<Note> resolve(String target, Note source);
}
