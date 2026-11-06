package self.research.ontology.common.db.sql;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] PROCESS_UNIQUE = new byte[5];
    private static final AtomicInteger COUNTER;

    static {
        RANDOM.nextBytes(PROCESS_UNIQUE);
        COUNTER = new AtomicInteger(RANDOM.nextInt(0x1000000));
    }

    private Ids() {
    }

    public static String newObjectId() {
        return newObjectId(System.currentTimeMillis() / 1000);
    }

    static String newObjectId(long epochSeconds) {
        byte[] bytes = new byte[12];
        bytes[0] = (byte) (epochSeconds >> 24);
        bytes[1] = (byte) (epochSeconds >> 16);
        bytes[2] = (byte) (epochSeconds >> 8);
        bytes[3] = (byte) epochSeconds;
        System.arraycopy(PROCESS_UNIQUE, 0, bytes, 4, 5);
        int count = COUNTER.incrementAndGet() & 0xFFFFFF;
        bytes[9] = (byte) (count >> 16);
        bytes[10] = (byte) (count >> 8);
        bytes[11] = (byte) count;
        return HexFormat.of().formatHex(bytes);
    }

    public static boolean looksLikeObjectId(String value) {
        return value != null && value.length() == 24 && value.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }
}
