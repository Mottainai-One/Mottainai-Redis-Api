package com.mottainai.redis;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Set;
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
            throw new IllegalStateException("JWT_SECRET inválido ou algoritmo não suportado.");
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
            long usuarioId = Long.parseLong(claims.getSubject());
            long empresaId = Long.parseLong(claims.get("empresa_id").toString());
            String role = claims.get("role", String.class).toUpperCase();
            if (usuarioId < 1 || empresaId < 1 || !ROLES.contains(role)) throw unauthorized();
            return new Principal(usuarioId, empresaId, role);
        } catch (RuntimeException exception) {
            throw unauthorized();
        }
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token de acesso inválido.");
    }

    public record Principal(long usuarioId, long empresaId, String role) {}
}
