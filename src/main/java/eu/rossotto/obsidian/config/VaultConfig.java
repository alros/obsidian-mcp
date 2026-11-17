package eu.rossotto.obsidian.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * One Obsidian vault, its visibility rules and its note creation settings.
 *
 * @param name         unique vault name, used as the first segment of every note id
 * @param path         vault root directory ({@code ~} is expanded)
 * @param allowedTags  if non-empty, only notes carrying at least one of these tags (or a nested child) are visible
 * @param ignoredTags  notes carrying any of these tags (or a nested child) are hidden; always wins over allowed
 * @param showUntagged whether notes without tags are visible
 * @param allowCreate  whether {@code create_note} may write new notes into this vault (default false)
 * @param inboxFolder  folder, relative to the vault root, where new notes are created (default {@value #DEFAULT_INBOX})
 * @param autoTag      tag added to every note created through the server, if set
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = false)
public record VaultConfig(
        @JsonProperty(value = "name", required = true) String name,
        @JsonProperty(value = "path", required = true) String path,
        @JsonProperty("allowed-tags") List<String> allowedTags,
        @JsonProperty("ignored-tags") List<String> ignoredTags,
        @JsonProperty("show-untagged") Boolean showUntagged,
        @JsonProperty("allow-create") Boolean allowCreate,
        @JsonProperty("inbox-folder") String inboxFolder,
        @JsonProperty("auto-tag") String autoTag) {

    public static final String DEFAULT_INBOX = "AI-input";

    public VaultConfig {
        allowedTags = normalize(allowedTags);
        ignoredTags = normalize(ignoredTags);
        showUntagged = showUntagged != null && showUntagged;
        allowCreate = allowCreate != null && allowCreate;
        inboxFolder = inboxFolder == null || inboxFolder.isBlank()
                ? DEFAULT_INBOX
                : inboxFolder.strip().replace('\\', '/').replaceAll("/+", "/").replaceAll("(.)/$", "$1");
        autoTag = autoTag == null || autoTag.isBlank() ? null : stripHashes(autoTag.strip());
    }

    /** A read-only vault with the default creation settings. */
    public VaultConfig(String name, String path, List<String> allowedTags, List<String> ignoredTags,
                       Boolean showUntagged) {
        this(name, path, allowedTags, ignoredTags, showUntagged, null, null, null);
    }

    public Path root() {
        String p = path;
        if (p.equals("~") || p.startsWith("~/")) {
            p = System.getProperty("user.home") + p.substring(1);
        }
        return Path.of(p).toAbsolutePath().normalize();
    }

    /** Absolute folder where new notes are created. */
    public Path inboxRoot() {
        return root().resolve(inboxFolder).normalize();
    }

    private static List<String> normalize(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(VaultConfig::normalizeTag)
                .distinct()
                .toList();
    }

    private static String stripHashes(String tag) {
        String t = tag;
        while (t.startsWith("#")) {
            t = t.substring(1);
        }
        return t;
    }

    /**
     * Checks that a tag can be written to a note's properties the way Obsidian accepts it: letters, digits,
     * {@code _}, {@code -} and {@code /} for nesting, with at least one non-digit. A leading {@code #} is dropped.
     *
     * @return the tag without leading {@code #}, case preserved
     * @throws IllegalArgumentException with a message meant for the caller
     */
    public static String validateTag(String tag) {
        String t = tag == null ? "" : stripHashes(tag.strip());
        if (t.isEmpty()) {
            throw new IllegalArgumentException("Tags must not be empty");
        }
        if (!t.matches("[\\p{L}\\p{M}\\p{N}_\\-/]+")) {
            throw new IllegalArgumentException("Invalid tag '" + tag
                    + "': use only letters, digits, '_', '-' and '/' (no spaces)");
        }
        if (t.startsWith("/") || t.endsWith("/") || t.contains("//")) {
            throw new IllegalArgumentException("Invalid tag '" + tag + "': empty nesting level");
        }
        if (t.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Invalid tag '" + tag + "': it needs at least one non-digit");
        }
        return t;
    }

    /** Tags are compared case-insensitively, without a leading '#' or trailing '/'. */
    public static String normalizeTag(String tag) {
        String t = stripHashes(tag.strip());
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.toLowerCase(Locale.ROOT);
    }
}
