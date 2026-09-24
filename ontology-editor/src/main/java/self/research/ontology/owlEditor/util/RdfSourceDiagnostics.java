package self.research.ontology.owlEditor.util;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.ParseErrorListener;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParseException;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;
import org.eclipse.rdf4j.rio.helpers.XMLParserSettings;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.LongPredicate;

public final class RdfSourceDiagnostics {

    public static final String BASE_URI = "http://ontocode.invalid/source";

    public enum Level { WARNING, ERROR, FATAL }

    public record Issue(Level level, String message, long line, long column) {}

    public record Result(List<Issue> issues, boolean capped, boolean timedOut, long fatalLine) {
        public boolean stoppedEarly() {
            return fatalLine != 0;
        }
    }

    private static final class Stop extends RuntimeException {
        Stop() {
            super(null, null, false, false);
        }
    }

    private RdfSourceDiagnostics() {
    }

    public static Result collect(Path file, RDFFormat format, LongPredicate lineFilter, int maxIssues,
                                 Duration timeout) throws IOException {
        Collector collector = new Collector(lineFilter, maxIssues, System.nanoTime() + timeout.toNanos());
        RDFParser parser = Rio.createParser(format);
        parser.getParserConfig().set(BasicParserSettings.PRESERVE_BNODE_IDS, true);
        parser.getParserConfig().set(BasicParserSettings.VERIFY_DATATYPE_VALUES, true);
        parser.getParserConfig().set(XMLParserSettings.SECURE_PROCESSING, true);
        parser.getParserConfig().set(XMLParserSettings.LOAD_EXTERNAL_DTD, false);
        parser.getParserConfig().set(XMLParserSettings.EXTERNAL_GENERAL_ENTITIES, false);
        parser.getParserConfig().set(XMLParserSettings.EXTERNAL_PARAMETER_ENTITIES, false);
        parser.getParserConfig().setNonFatalErrors(new HashSet<>(parser.getSupportedSettings()));
        parser.setParseErrorListener(collector);
        parser.setRDFHandler(new AbstractRDFHandler() {
            private long statements;

            @Override
            public void handleStatement(Statement st) {
                if ((++statements & 0xFFF) == 0) {
                    collector.checkDeadline();
                }
            }
        });
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            parser.parse(reader, BASE_URI);
        } catch (Stop stop) {
            return collector.result();
        } catch (RDFParseException e) {
            if (collector.stopped) {
                return collector.result();
            }
            collector.recordFatal(e.getMessage(), e.getLineNumber(), e.getColumnNumber());
        } catch (RuntimeException e) {
            if (collector.stopped) {
                return collector.result();
            }
            collector.recordFatal(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), -1, -1);
        }
        return collector.result();
    }

    private static final class Collector implements ParseErrorListener {
        private final LongPredicate lineFilter;
        private final int maxIssues;
        private final long deadlineNanos;
        private final List<Issue> issues = new ArrayList<>();
        private boolean capped;
        private boolean timedOut;
        private boolean stopped;
        private long fatalLine;

        Collector(LongPredicate lineFilter, int maxIssues, long deadlineNanos) {
            this.lineFilter = lineFilter;
            this.maxIssues = maxIssues;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public void warning(String msg, long lineNo, long colNo) {
            add(Level.WARNING, msg, lineNo, colNo);
        }

        @Override
        public void error(String msg, long lineNo, long colNo) {
            add(Level.ERROR, msg, lineNo, colNo);
        }

        @Override
        public void fatalError(String msg, long lineNo, long colNo) {
            fatalLine = lineNo > 0 ? lineNo : -1;
            add(Level.FATAL, msg, lineNo, colNo);
        }

        void recordFatal(String msg, long lineNo, long colNo) {
            if (fatalLine != 0) {
                return;
            }
            fatalError(msg, lineNo, colNo);
        }

        void checkDeadline() {
            if (System.nanoTime() > deadlineNanos) {
                timedOut = true;
                stopped = true;
                throw new Stop();
            }
        }

        private void add(Level level, String msg, long lineNo, long colNo) {
            if (stopped || !lineFilter.test(lineNo)) {
                return;
            }
            if (issues.size() >= maxIssues) {
                capped = true;
                if (level != Level.FATAL) {
                    stopped = true;
                    throw new Stop();
                }
                return;
            }
            issues.add(new Issue(level, clean(msg, lineNo), lineNo, colNo));
            checkDeadline();
        }

        private static String clean(String msg, long lineNo) {
            String text = msg == null ? "" : msg.trim();
            String suffix = " [line " + lineNo;
            int at = text.lastIndexOf(suffix);
            if (lineNo > 0 && at > 0) {
                text = text.substring(0, at).trim();
            }
            return text.length() > 500 ? text.substring(0, 500) + "..." : text;
        }

        Result result() {
            return new Result(List.copyOf(issues), capped, timedOut, fatalLine);
        }
    }
}
