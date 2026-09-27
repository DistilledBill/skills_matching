package com.example.resumescreening.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param jobsDir    folder of job spec YAML files, loaded at startup
 * @param resumesDir root that folder-based screening is confined to
 */
@ConfigurationProperties("screening")
public record ScreeningProperties(Path jobsDir, Path resumesDir) {
}
