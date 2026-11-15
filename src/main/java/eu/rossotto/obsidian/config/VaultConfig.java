package eu.rossotto.obsidian.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * One Obsidian vault and its visibility rules.
 *
 * @param name         unique vault name, used as the first segment of every note id
 * @param path         vault root directory ({@code ~} is expanded)
 * @param allowedTags  if non-empty, only notes carrying at least one of these tags (or a nested child) are visible
 * @param ignoredTags  notes carrying any of these tags (or a nested child) are hidden; always wins over allowed
 * @param showUntagged whether notes without tags are visible
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = false)
public record VaultConfig(
        @JsonProperty(value = "name", required = true) String name,
        @JsonProperty(value = "path", required = true) String path,
        @JsonProperty("allowed-tags") List<String> allowedTags,
        @JsonProperty("ignored-tags") List<String> ignoredTags,
        @JsonProperty("show-untagged") Boolean showUntagged) {

    public VaultConfig {
        allowedTags = normalize(allowedTags);
        ignoredTags = normalize(ignoredTags);
        showUntagged = showUntagged != null && showUntagged;
    }

    public Path root() {
        String p = path;
        if (p.equals("~") || p.startsWith("~/")) {
            p = System.getProperty("user.home") + p.substring(1);
        }
        return Path.of(p).toAbsolutePath().normalize();
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

    /** Tags are compared case-insensitively, without a leading '#' or trailing '/'. */
    public static String normalizeTag(String tag) {
        String t = tag.strip();
        while (t.startsWith("#")) {
            t = t.substring(1);
        }
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.toLowerCase(Locale.ROOT);
    }
}
