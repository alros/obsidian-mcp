package eu.rossotto.obsidian.vault;

import java.nio.file.Path;
import java.util.List;

/**
 * An indexed note.
 *
 * @param id       {@code <vault>/<relative path>}, unique across all vaults
 * @param vault    vault name
 * @param path     path relative to the vault root, always with '/' separators
 * @param title    frontmatter {@code title}, or the file name without {@code .md}
 * @param tags     frontmatter tags as written (without '#')
 * @param file     absolute file location
 * @param modified last modification time in millis
 * @param size     file size in bytes
 * @param visible  whether the vault rules expose this note
 */
public record Note(String id, String vault, String path, String title, List<String> tags,
                   Path file, long modified, long size, boolean visible) {

    /** File name without extension, the key Obsidian uses to resolve {@code [[links]]}. */
    public String basename() {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    /** Relative path without the {@code .md} extension. */
    public String pathWithoutExtension() {
        return path.endsWith(".md") ? path.substring(0, path.length() - 3) : path;
    }

    public String folder() {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }
}
