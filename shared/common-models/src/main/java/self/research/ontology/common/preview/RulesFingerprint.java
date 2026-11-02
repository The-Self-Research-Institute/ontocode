package self.research.ontology.common.preview;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class RulesFingerprint {

    public static final String NONE = "none";

    private RulesFingerprint() {
    }

    public static String of(List<String[]> nameAndText) {
        if (nameAndText == null || nameAndText.isEmpty()) {
            return NONE;
        }
        List<String[]> sorted = new ArrayList<>(nameAndText);
        sorted.sort(Comparator.<String[], String>comparing(rule -> String.valueOf(rule[0]))
                .thenComparing(rule -> String.valueOf(rule[1])));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String[] rule : sorted) {
                digest.update(String.valueOf(rule[0]).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(String.valueOf(rule[1]).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 10);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
