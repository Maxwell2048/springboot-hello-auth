package com.example.helloauth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Typed view of the {@code app.*} settings in application.yml. */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(DemoUser demoUser, Jwt jwt) {

    public record DemoUser(@NotBlank String username, @NotBlank String password) {}

    /** HS256 needs a key of at least 256 bits, hence the 32-character minimum. */
    public record Jwt(@NotBlank @Size(min = 32) String secret, Duration ttl, @NotBlank String issuer) {}
}
