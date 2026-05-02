package self.research.ontology.auth.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.security.core.GrantedAuthority;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    @Value("${jwt.secret}")
    private String SECRET_KEY;

    @Value("${jwt.expiration}")
    private long EXPIRATION_TIME; // in milliseconds

    @PostConstruct
    public void validateSecrets() {
        if (SECRET_KEY == null || SECRET_KEY.isBlank()) {
            throw new IllegalStateException(
                "[SECURITY] jwt.secret (JWT_SECRET) must be set. " +
                "Generate one with: openssl rand -base64 48");
        }
        byte[] decoded;
        try {
            decoded = Decoders.BASE64.decode(SECRET_KEY);
        } catch (Exception e) {
            throw new IllegalStateException(
                "[SECURITY] jwt.secret is not valid Base64. " +
                "Generate one with: openssl rand -base64 48");
        }
        if (decoded.length < 32) {
            throw new IllegalStateException(
                "[SECURITY] jwt.secret must decode to at least 256 bits (32 bytes). " +
                "Current length: " + decoded.length + " bytes.");
        }
        log.info("JWT secret validated — {} bytes.", decoded.length);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser().setSigningKey(getSigningKey()).build().parseClaimsJws(token).getBody();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public String generateToken(UserDetails userDetails, String email) {
        return generateToken(userDetails, email, null);
    }

    public String generateToken(UserDetails userDetails, String email, String userId) {
        return generateToken(userDetails, email, userId, null);
    }

    public String generateToken(UserDetails userDetails, String email, String userId, String planName) {
        Map<String, Object> claims = new HashMap<>();
        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
        claims.put("roles", roles);
        claims.put("email", email);
        claims.put("isAdmin", roles.contains("ROLE_ADMIN"));
        if (userId != null) {
            claims.put("userId", userId);
        }
        // Include plan so downstream services can enforce access without a DB call
        claims.put("plan", planName != null ? planName.toUpperCase() : "FREE");
        return createToken(claims, userDetails.getUsername());
    }

    // Overloaded method for workspace-scoped tokens
    public String generateToken(String username, Map<String, Object> additionalClaims) {
        return createToken(additionalClaims, username);
    }

    private String createToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }

    private Key getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(SECRET_KEY);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}