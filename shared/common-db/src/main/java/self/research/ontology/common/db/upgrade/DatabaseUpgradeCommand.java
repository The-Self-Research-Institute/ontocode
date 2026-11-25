package self.research.ontology.common.db.upgrade;

import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.Map;

public final class DatabaseUpgradeCommand {

    public static final String FLAG = "--upgrade-database";
    public static final String SOURCE_URL = "ONTOCODE_UPGRADE_SOURCE_URL";
    public static final String TARGET_URL = "ONTOCODE_UPGRADE_TARGET_URL";
    public static final String USER = "ONTOCODE_DB_USER";
    public static final String PASSWORD = "ONTOCODE_DB_PASSWORD";

    public static final int OK = 0;
    public static final int FAILED = 1;
    public static final int MISMATCH = 2;
    public static final int SCHEMA_TOO_NEW = 3;

    private DatabaseUpgradeCommand() {
    }

    public static boolean requested(String[] args) {
        return args.length > 0 && FLAG.equals(args[0]);
    }

    public static int run(Map<String, String> env, PrintStream out) {
        quietLogging();
        String source = env.get(SOURCE_URL);
        String target = env.get(TARGET_URL);
        String password = env.get(PASSWORD);
        String user = env.getOrDefault(USER, "ontocode");
        for (Map.Entry<String, String> required : Map.of(SOURCE_URL, String.valueOf(source),
                TARGET_URL, String.valueOf(target), PASSWORD, String.valueOf(password)).entrySet()) {
            if ("null".equals(required.getValue()) || required.getValue().isBlank()) {
                out.println("[upgrade] FAILED: " + required.getKey() + " is not set");
                return FAILED;
            }
        }
        try {
            DatabaseCopy.Result result = DatabaseCopy.copy(dataSource(source, user, password),
                    dataSource(target, user, password), line -> out.println("[upgrade] " + line));
            out.println("[upgrade] OK: " + result.tables() + " tables, " + result.rows() + " rows, schema "
                    + result.schemaVersion() + ", " + result.millis() + " ms");
            return OK;
        } catch (DatabaseCopy.SchemaTooNewException e) {
            out.println("[upgrade] FAILED: " + e.getMessage());
            return SCHEMA_TOO_NEW;
        } catch (DatabaseCopy.VerificationException e) {
            out.println("[upgrade] FAILED: " + e.getMessage());
            return MISMATCH;
        } catch (Exception e) {
            out.println("[upgrade] FAILED: " + e.getMessage());
            return FAILED;
        }
    }

    private static PGSimpleDataSource dataSource(String url, String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static void quietLogging() {
        if (LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) instanceof ch.qos.logback.classic.Logger root) {
            root.setLevel(ch.qos.logback.classic.Level.WARN);
        }
    }
}
