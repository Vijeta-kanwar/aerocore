package com.aerocore.security;

import com.aerocore.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and verifies access tokens.
 *
 * <p>Stateless by design, and that's a direct consequence of running three replicas. A
 * session held in one pod's memory is invisible to the other two, so a round-robin load
 * balancer would log people out on two requests in three. Redis would fix it by adding
 * infrastructure; sticky sessions would fix it by tying a user to a pod Kubernetes may kill
 * mid-rollout. A signed token needs neither: any pod can verify it with the shared secret and
 * no lookup at all.
 *
 * <p>The payload is base64, not encryption -- anyone can decode it in a browser console. So
 * it carries only things that aren't secret: a user id, a role, an expiry. What the signature
 * buys is not privacy but forgery resistance. Change the role claim to ADMIN and the HMAC no
 * longer matches, because recomputing it needs a secret the holder doesn't have.
 */
@Service
public class JwtService {

    private static final String CLAIM_ROLE = "role";

    private final SecretKey signingKey;
    private final Duration tokenLifetime;

    public JwtService(@Value("${aerocore.security.jwt-secret}") String secret,
                      @Value("${aerocore.security.token-lifetime-minutes:30}") long lifetimeMinutes) {
        // HMAC-SHA256 needs at least 256 bits. Anything shorter is rejected here rather than
        // silently weakening every token the application ever issues.
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenLifetime = Duration.ofMinutes(lifetimeMinutes);
    }

    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(tokenLifetime)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Verifies a token and returns who it claims to be, or empty if it can't be trusted.
     *
     * <p>Every rejection reason -- bad signature, expired, malformed -- collapses to the same
     * empty result on purpose. The caller's decision is identical in each case, and telling an
     * attacker which part of their forgery failed is free help.
     */
    public Optional<AuthenticatedUser> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return Optional.of(new AuthenticatedUser(
                    Long.valueOf(claims.getSubject()),
                    claims.get(CLAIM_ROLE, String.class)));
        } catch (JwtException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public long getTokenLifetimeSeconds() {
        return tokenLifetime.toSeconds();
    }

    public record AuthenticatedUser(Long userId, String role) { }
}
