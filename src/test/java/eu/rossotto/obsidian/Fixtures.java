package eu.rossotto.obsidian;

import eu.rossotto.obsidian.config.ServerConfig;
import eu.rossotto.obsidian.config.VaultConfig;
import eu.rossotto.obsidian.service.NoteRepository;

import java.nio.file.Path;
import java.util.List;

public final class Fixtures {

    public static final Path VAULTS = Path.of("src/test/resources/vaults").toAbsolutePath();

    private Fixtures() {
    }

    public static ServerConfig config() {
        return new ServerConfig(List.of(
                new VaultConfig("work", VAULTS.resolve("work").toString(),
                        List.of("project", "howto"), List.of("private"), false),
                new VaultConfig("personal", VAULTS.resolve("personal").toString(),
                        List.of(), List.of("journal"), true)));
    }

    public static NoteRepository repository() {
        NoteRepository repo = new NoteRepository();
        repo.configure(config());
        return repo;
    }
}
