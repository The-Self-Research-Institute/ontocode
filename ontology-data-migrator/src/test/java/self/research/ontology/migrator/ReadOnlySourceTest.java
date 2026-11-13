package self.research.ontology.migrator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlySourceTest {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insertOne|insertMany|updateOne|updateMany|replaceOne|findOneAndUpdate|findOneAndReplace|findOneAndDelete|"
                    + "deleteOne|deleteMany|bulkWrite|drop|dropIndex|dropIndexes|dropDatabase|createCollection|createIndex|"
                    + "createIndexes|renameCollection|uploadFromStream|openUploadStream|delete|rename|runCommand|"
                    + "\\$out|\\$merge)\\b");

    private static Path mainSources() {
        Path here = Path.of("").toAbsolutePath();
        Path module = Files.isDirectory(here.resolve("src/main/java")) ? here : here.resolve("ontology-data-migrator");
        return module.resolve("src/main/java");
    }

    @Test
    void noMongoWriteCallExistsAnywhereInTheMainSources() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSources())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (file.toString().replace('\\', '/').contains("/persistence/")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (FORBIDDEN.matcher(line).find() && !line.trim().startsWith("import")) {
                        offenders.add(file.getFileName() + ":" + (i + 1) + ": " + line.trim());
                    }
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void theMongoDriverIsOnlyUsedInTheMongoPackage() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSources())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                boolean inMongoPackage = file.toString().replace('\\', '/').contains("/migrator/mongo/");
                if (!inMongoPackage && Files.readString(file).contains("import com.mongodb.")) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void theClientIsConfiguredForReadingOnly() throws IOException {
        String source = Files.readString(mainSources().resolve("self/research/ontology/migrator/mongo/MongoSourceReader.java"));
        assertTrue(source.contains("ReadPreference.secondaryPreferred()"));
        assertTrue(source.contains("applicationName(\"ontocode-data-migrator\")"));
    }

    @Test
    void theConnectionAddressIsNeverPrinted() throws IOException {
        try (Stream<Path> files = Files.walk(mainSources())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                for (String line : Files.readAllLines(file)) {
                    boolean prints = line.contains("System.out") || line.contains("System.err") || line.contains("LOG.")
                            || line.contains("println");
                    assertTrue(!(prints && line.contains("mongoUri")), file.getFileName() + ": " + line);
                }
            }
        }
    }
}
