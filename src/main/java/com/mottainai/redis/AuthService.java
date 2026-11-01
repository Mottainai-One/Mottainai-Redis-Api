package com.mottainai.redis;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.Locale;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private static final Set<String> ROLES = Set.of("CLIENTE", "ESTOQUISTA", "GERENTE", "DONO");
    private final SecretKey key;

    public AuthService(@Value("${mottainai.jwt.secret}") String secret,
                       @Value("${mottainai.jwt.algorithm}") String algorithm) {
        if (secret.length() < 32 || !"HS256".equals(algorithm)) {
            throw new IllegalStateException("Invalid JWT_SECRET or unsupported algorithm.");
        }
        key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Principal principal(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw unauthorized();
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(authorization.substring(7)).getPayload();
            if (claims.getExpiration() == null) {
                throw unauthorized();
            }
            Object explicitUserId = claims.get("usuario_id");
            String tokenUse = claims.get("use", String.class);
            if ((explicitUserId != null && !"access".equals(tokenUse))
                    || (tokenUse != null && !"access".equals(tokenUse))) {
                throw unauthorized();
            }
            if (claims.getSubject() == null || claims.getSubject().isBlank()) throw unauthorized();
            long userId = Long.parseLong(explicitUserId == null
                    ? claims.getSubject() : explicitUserId.toString());
            long companyId = Long.parseLong(claims.get("empresa_id").toString());
            String role = claims.get("role", String.class).toUpperCase(Locale.ROOT);
            if (userId < 1 || companyId < 1 || !ROLES.contains(role)) throw unauthorized();
            return new Principal(userId, companyId, role);
        } catch (RuntimeException exception) {
            throw unauthorized();
        }
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid access token.");
    }

    public record Principal(long userId, long companyId, String role) {}
}
