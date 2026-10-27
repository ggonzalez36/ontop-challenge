package com.ontop.transfers;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Signs HS256 tokens with the development secret from application.yml, as the identity provider would.
 */
public final class TestTokens {

    public static final String DEV_SECRET = "b250b3AtY2hhbGxlbmdlLWRldi1vbmx5LWp3dC1zaWduaW5nLWtleS0wMDAx";

    private TestTokens() {
    }

    public static String forUser(long userId) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(String.valueOf(userId))
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(Base64.getDecoder().decode(DEV_SECRET)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
