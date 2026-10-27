package com.ontop.transfers.adapter.in.web.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Either {@code issuerUri} (production: tokens from the identity provider, keys fetched from its JWKS) or
 * {@code hmacSecret} (local development and tests: HS256 with a shared secret, base64).
 */
@ConfigurationProperties("ontop.security.jwt")
public record JwtProperties(String issuerUri, String hmacSecret) {
}
