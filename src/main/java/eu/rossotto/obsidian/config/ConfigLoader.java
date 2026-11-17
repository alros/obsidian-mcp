package eu.rossotto.obsidian.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Locates and parses the YAML configuration.
 *
 * <p>Lookup order (first existing file wins):
 * <ol>
 *   <li>{@code OBSIDIAN_MCP_CONFIG} environment variable or {@code obsidian.mcp.config} system property</li>
 *   <li>{@code ./.config/config.yaml} (relative to the working directory)</li>
 *   <li>{@code ~/.config/obsidian-mcp/config.yaml}</li>
 * </ol>
 */
public final class ConfigLoader {

    public static final String ENV_VAR = "OBSIDIAN_MCP_CONFIG";
    public static final String SYS_PROP = "obsidian.mcp.config";

    private static final ObjectMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private ConfigLoader() {
    }

    /**
     * A possible configuration location.
     *
     * @param path   absolute, normalized path
     * @param source human-readable origin, e.g. "OBSIDIAN_MCP_CONFIG environment variable"
     */
    public record Candidate(Path path, String source) {
    }

    public static List<Candidate> candidates() {
        Optional<Candidate> explicit = explicitLocation();
        if (explicit.isPresent()) {
            // An explicit location must exist: silently falling back would be surprising.
            return List.of(explicit.get());
        }
        List<Candidate> result = new ArrayList<>();
        result.add(new Candidate(absolute(Path.of(".config", "config.yaml")), "working directory"));
        result.add(new Candidate(absolute(Path.of(System.getProperty("user.home"), ".config", "obsidian-mcp",
                "config.yaml")), "user home"));
        return result;
    }

    private static Optional<Candidate> explicitLocation() {
        String value = System.getProperty(SYS_PROP);
        if (value != null && !value.isBlank()) {
            return Optional.of(new Candidate(absolute(Path.of(value)), SYS_PROP + " system property"));
        }
        value = System.getenv(ENV_VAR);
        if (value != null && !value.isBlank()) {
            return Optional.of(new Candidate(absolute(Path.of(value)), ENV_VAR + " environment variable"));
        }
        return Optional.empty();
    }

    private static Path absolute(Path p) {
        String s = p.toString();
        if (s.equals("~") || s.startsWith("~/")) {
            p = Path.of(System.getProperty("user.home") + s.substring(1));
        }
        return p.toAbsolutePath().normalize();
    }

    /** Finds the first existing configuration file. */
    public static Optional<Candidate> locate() {
        return candidates().stream().filter(c -> Files.isRegularFile(c.path())).findFirst();
    }

    /** Where a located file really lives, following symlinks; falls back to the path itself. */
    public static Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }

    public static ServerConfig load(Path file) throws ConfigException {
        ServerConfig config;
        try {
            config = YAML.readValue(file.toFile(), ServerConfig.class);
        } catch (IOException e) {
            throw new ConfigException("Cannot parse " + file + ": " + e.getMessage(), e);
        }
        if (config == null) {
            throw new ConfigException(file + " is empty");
        }
        validate(config, file);
        return config;
    }

    static void validate(ServerConfig config, Path file) throws ConfigException {
        if (config.vaults().isEmpty()) {
            throw new ConfigException(file + " declares no vaults");
        }
        Set<String> names = new HashSet<>();
        for (VaultConfig v : config.vaults()) {
            if (v.name() == null || v.name().isBlank() || v.name().contains("/")) {
                throw new ConfigException("Invalid vault name '" + v.name() + "' (must be non-empty and contain no '/')");
            }
            if (!names.add(v.name())) {
                throw new ConfigException("Duplicate vault name '" + v.name() + "'");
            }
            if (!Files.isDirectory(v.root())) {
                throw new ConfigException("Vault '" + v.name() + "': directory not found: " + v.root());
            }
            validateInbox(v);
            if (v.autoTag() != null) {
                try {
                    VaultConfig.validateTag(v.autoTag());
                } catch (IllegalArgumentException e) {
                    throw new ConfigException("Vault '" + v.name() + "': auto-tag: " + e.getMessage());
                }
            }
        }
    }

    private static void validateInbox(VaultConfig v) throws ConfigException {
        String folder = v.inboxFolder();
        if (folder.startsWith("/") || folder.startsWith("~") || folder.matches("^[A-Za-z]:.*")) {
            throw new ConfigException("Vault '" + v.name() + "': inbox-folder must be relative to the vault, got '"
                    + folder + "'");
        }
        for (String segment : folder.split("/")) {
            // Covers "." and "..", and hidden folders, which the index never scans.
            if (segment.startsWith(".")) {
                throw new ConfigException("Vault '" + v.name() + "': inbox-folder must stay inside the vault and "
                        + "must not be a hidden folder, got '" + folder + "'");
            }
        }
    }

    public static final class ConfigException extends Exception {
        public ConfigException(String message) {
            super(message);
        }

        public ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
