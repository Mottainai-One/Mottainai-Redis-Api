package com.mottainai.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AuthServiceTest {
    private static final String SECRET = "local_test_secret_with_more_than_32_characters";
    private final AuthService auth = new AuthService(SECRET, "HS256");

    @Test
    void acceptsVerifiedTokenWithRequiredClaims() {
        String token = Jwts.builder()
                .subject("10")
                .claim("empresa_id", 1)
                .claim("role", "ESTOQUISTA")
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        AuthService.Principal principal = auth.principal("Bearer " + token);

        assertEquals(10, principal.userId());
        assertEquals(1, principal.companyId());
        assertEquals("ESTOQUISTA", principal.role());
    }

    @Test
    void rejectsTokenWithoutExpiration() {
        String token = Jwts.builder()
                .subject("10")
                .claim("empresa_id", 1)
                .claim("role", "ESTOQUISTA")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class, () -> auth.principal("Bearer " + token));

        assertEquals(401, error.getStatusCode().value());
    }
}
