package eu.rossotto.obsidian.vault;

import eu.rossotto.obsidian.config.VaultConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultRefreshTest {

    @TempDir
    Path dir;

    private void write(String name, String text, long mtime) throws Exception {
        Path f = dir.resolve(name);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtime));
    }

    @Test
    void picksUpChangesIncrementally() throws Exception {
        write("A.md", "---\ntags: [ok]\n---\nlinks to [[B]]", 1000);
        write("B.md", "---\ntags: [ok]\n---\nb", 1000);
        Vault vault = new Vault(new VaultConfig("v", dir.toString(), List.of(), List.of("hide"), false));

        assertTrue(vault.refresh());
        Vault.Snapshot first = vault.snapshot();
        Note a = first.visibleByPath("A.md").orElseThrow();
        assertEquals("links to [[B]]", first.content(a).rendered().content());

        assertFalse(vault.refresh(), "nothing changed");
        assertSame(first, vault.snapshot());

        // hiding B changes how A is rendered, and the unchanged Note instance for A is reused
        write("B.md", "---\ntags: [ok, hide]\n---\nb", 2000);
        assertTrue(vault.refresh());
        Vault.Snapshot second = vault.snapshot();
        assertSame(a, second.visibleByPath("A.md").orElseThrow());
        assertTrue(second.visibleByPath("B.md").isEmpty());
        assertEquals("links to B", second.content(a).rendered().content());

        Files.delete(dir.resolve("A.md"));
        assertTrue(vault.refresh());
        assertTrue(vault.snapshot().visibleNotes().isEmpty());
    }
}
