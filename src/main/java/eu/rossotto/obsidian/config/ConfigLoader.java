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

    public static List<Path> candidates() {
        List<Path> result = new ArrayList<>();
        explicitLocation().ifPresent(result::add);
        result.add(Path.of(".config", "config.yaml").toAbsolutePath().normalize());
        result.add(Path.of(System.getProperty("user.home"), ".config", "obsidian-mcp", "config.yaml"));
        return result;
    }

    private static Optional<Path> explicitLocation() {
        String value = System.getProperty(SYS_PROP);
        if (value == null || value.isBlank()) {
            value = System.getenv(ENV_VAR);
        }
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value).toAbsolutePath());
    }

    /** Finds the first existing configuration file. */
    public static Optional<Path> locate() {
        Optional<Path> explicit = explicitLocation();
        if (explicit.isPresent()) {
            // An explicit location must exist: silently falling back would be surprising.
            return explicit.filter(Files::isRegularFile);
        }
        return candidates().stream().filter(Files::isRegularFile).findFirst();
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
