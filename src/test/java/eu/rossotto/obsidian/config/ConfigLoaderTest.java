package eu.rossotto.obsidian.config;

import eu.rossotto.obsidian.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir
    Path tmp;

    private Path write(String yaml) throws Exception {
        Path f = tmp.resolve("config.yaml");
        Files.writeString(f, yaml);
        return f;
    }

    @Test
    void parsesFullConfig() throws Exception {
        ServerConfig c = ConfigLoader.load(write("""
                vaults:
                  - name: work
                    path: %s
                    allowed-tags: [Project, "#howto/"]
                    ignored-tags: [private]
                    show-untagged: true
                  - name: personal
                    path: %s
                """.formatted(Fixtures.VAULTS.resolve("work"), Fixtures.VAULTS.resolve("personal"))));
        VaultConfig work = c.vaults().getFirst();
        assertEquals(List.of("project", "howto"), work.allowedTags());
        assertEquals(List.of("private"), work.ignoredTags());
        assertTrue(work.showUntagged());
        VaultConfig personal = c.vaults().get(1);
        assertTrue(personal.allowedTags().isEmpty());
        assertFalse(personal.showUntagged(), "untagged notes are hidden by default");
    }

    @Test
    void explicitLocationIsAbsoluteAndExclusive() throws Exception {
        Path f = write("vaults: []");
        System.setProperty(ConfigLoader.SYS_PROP, tmp.resolve("sub/../config.yaml").toString());
        try {
            ConfigLoader.Candidate c = ConfigLoader.locate().orElseThrow();
            assertEquals(f.toAbsolutePath().normalize(), c.path());
            assertEquals("obsidian.mcp.config system property", c.source());

            System.setProperty(ConfigLoader.SYS_PROP, tmp.resolve("missing.yaml").toString());
            assertTrue(ConfigLoader.locate().isEmpty(), "no silent fallback when the explicit file is missing");
            assertEquals(1, ConfigLoader.candidates().size());
        } finally {
            System.clearProperty(ConfigLoader.SYS_PROP);
        }
    }

    @Test
    void defaultCandidatesAreAbsolute() {
        List<ConfigLoader.Candidate> c = ConfigLoader.candidates();
        assertEquals(Path.of(".config/config.yaml").toAbsolutePath().normalize(), c.get(0).path());
        assertEquals(Path.of(System.getProperty("user.home"), ".config/obsidian-mcp/config.yaml"), c.get(1).path());
        assertTrue(c.stream().allMatch(x -> x.path().isAbsolute()));
    }

    @Test
    void expandsHome() {
        VaultConfig v = new VaultConfig("x", "~/notes", null, null, null);
        assertEquals(Path.of(System.getProperty("user.home"), "notes"), v.root());
    }

    @Test
    void rejectsInvalidConfigs() throws Exception {
        String dir = Fixtures.VAULTS.resolve("work").toString();
        assertThrows(ConfigLoader.ConfigException.class, () -> ConfigLoader.load(write("vaults: []")));
        assertThrows(ConfigLoader.ConfigException.class, () -> ConfigLoader.load(write(
                "vaults:\n  - {name: a, path: " + dir + "}\n  - {name: a, path: " + dir + "}")));
        assertThrows(ConfigLoader.ConfigException.class, () -> ConfigLoader.load(write(
                "vaults:\n  - {name: a, path: /does/not/exist}")));
        assertThrows(ConfigLoader.ConfigException.class, () -> ConfigLoader.load(write(
                "vaults:\n  - {name: a, path: " + dir + ", allowed_tags: [x]}")), "typos are reported");
        assertThrows(ConfigLoader.ConfigException.class, () -> ConfigLoader.load(write(
                "vaults:\n  - {name: a/b, path: " + dir + "}")));
    }
}
