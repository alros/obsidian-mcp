package eu.rossotto.obsidian.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** Root of the YAML configuration file. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = false)
public record ServerConfig(@JsonProperty("vaults") List<VaultConfig> vaults) {

    public ServerConfig {
        vaults = vaults == null ? List.of() : List.copyOf(vaults);
    }
}
