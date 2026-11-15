package eu.rossotto.obsidian.vault;

import eu.rossotto.obsidian.config.VaultConfig;

import java.util.Collection;
import java.util.List;

/**
 * Visibility rules of a vault.
 *
 * <ul>
 *   <li>Untagged notes are visible only when {@code show-untagged} is true.</li>
 *   <li>A note carrying any ignored tag is hidden. Ignored always wins.</li>
 *   <li>If the allow list is empty every remaining note is visible, otherwise at least one tag must be allowed.</li>
 * </ul>
 * Matching is case-insensitive and hierarchical: rule {@code project} matches {@code project} and
 * {@code project/alpha}, but not {@code projects}.
 */
public record TagRules(List<String> allowed, List<String> ignored, boolean showUntagged) {

    public static TagRules of(VaultConfig config) {
        return new TagRules(config.allowedTags(), config.ignoredTags(), config.showUntagged());
    }

    public boolean isVisible(Collection<String> tags) {
        if (tags.isEmpty()) {
            return showUntagged;
        }
        if (tags.stream().anyMatch(t -> matchesAny(t, ignored))) {
            return false;
        }
        return allowed.isEmpty() || tags.stream().anyMatch(t -> matchesAny(t, allowed));
    }

    static boolean matchesAny(String tag, List<String> rules) {
        String t = VaultConfig.normalizeTag(tag);
        for (String rule : rules) {
            if (matches(t, rule)) {
                return true;
            }
        }
        return false;
    }

    /** {@code tag} and {@code rule} must already be normalized. */
    public static boolean matches(String tag, String rule) {
        return tag.equals(rule) || (tag.startsWith(rule) && tag.charAt(rule.length()) == '/');
    }
}
