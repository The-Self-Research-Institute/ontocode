package self.research.ontology.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Issues signed desktop-license files (Ed25519).
 *
 * The web/cloud auth service signs a license payload with its private key; the
 * desktop Electron shell verifies the signature with the bundled public key.
 * The license is the single source of truth for the desktop user's identity
 * (name, email, plan, validity).
 *
 * The private key is supplied via {@code license.signing.private-key} (PEM, or
 * base64-of-PEM for env friendliness). Generate a keypair with
 * {@code node scripts/generate-license-keys.js}. NEVER commit the private key.
 */
@Service
public class LicenseSigningService {

    private static final Logger log = LoggerFactory.getLogger(LicenseSigningService.class);
    private static final int LICENSE_VERSION = 1;

    @Value("${license.signing.private-key:}")
    private String privateKeyConfig;

    /** True when a signing key is configured (license issuance available). */
    public boolean isConfigured() {
        return privateKeyConfig != null && !privateKeyConfig.isBlank();
    }

    /**
     * Build and sign a license payload for the given identity/plan.
     * The returned map (payload + "signature") is serialised to the .lic file.
     */
    public Map<String, Object> issue(String name, String email, String plan,
                                     LocalDateTime expiresAt, Map<String, Object> features) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", LICENSE_VERSION);
        payload.put("plan", plan == null ? "FREE" : plan.toUpperCase());
        payload.put("email", email == null ? "" : email);
        payload.put("name", name == null ? "" : name);
        payload.put("issuedAt", LocalDateTime.now().toString());
        payload.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        if (features != null) {
            payload.put("features", features);
        }
        payload.put("signature", sign(canonical(payload)));
        return payload;
    }

    /**
     * Deterministic string signed/verified on both sides. Must match the
     * canonical() implementation in electron-app/main.js exactly.
     */
    private String canonical(Map<String, Object> p) {
        return String.join("\n",
                str(p.get("version")),
                str(p.get("plan")),
                str(p.get("email")),
                str(p.get("name")),
                str(p.get("issuedAt")),
                str(p.get("expiresAt")));
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String sign(String canonical) {
        try {
            PrivateKey key = loadPrivateKey();
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            log.error("Failed to sign license", e);
            throw new IllegalStateException("Failed to sign license: " + e.getMessage(), e);
        }
    }

    private PrivateKey loadPrivateKey() throws Exception {
        String pem = privateKeyConfig.trim();
        // Allow base64-of-PEM (env-friendly) or raw PEM.
        if (!pem.contains("BEGIN")) {
            pem = new String(Base64.getDecoder().decode(pem), StandardCharsets.UTF_8);
        }
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        return KeyFactory.getInstance("Ed25519").generatePrivate(spec);
    }
}
