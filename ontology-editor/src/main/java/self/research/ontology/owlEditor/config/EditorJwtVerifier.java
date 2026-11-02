package self.research.ontology.owlEditor.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;

public final class EditorJwtVerifier {

    private EditorJwtVerifier() {
    }

    public static Claims verify(String base64Secret, String token) {
        byte[] keyBytes = Decoders.BASE64.decode(base64Secret);
        SecretKey key = Keys.hmacShaKeyFor(keyBytes);
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token.trim())
                .getPayload();
        if (claims.getSubject() == null || claims.getSubject().isBlank()) {
            throw new IllegalArgumentException("missing subject");
        }
        return claims;
    }
}
