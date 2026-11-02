package self.research.ontology.owlEditor.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;

@Component
public class JwtVerifier {

    private static final String BEARER_PREFIX = "Bearer ";

    private final String secret;

    public JwtVerifier(@Value("${jwt.secret:}") String secret) {
        this.secret = secret;
    }

    public boolean configured() {
        return secret != null && !secret.isBlank();
    }

    public static boolean isBearer(String authHeader) {
        return authHeader != null && authHeader.startsWith(BEARER_PREFIX);
    }

    public Claims verify(String authHeader) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(authHeader.substring(BEARER_PREFIX.length()).trim())
                .getPayload();
        if (claims.getSubject() == null || claims.getSubject().isBlank()) {
            throw new IllegalArgumentException("missing subject");
        }
        return claims;
    }
}
