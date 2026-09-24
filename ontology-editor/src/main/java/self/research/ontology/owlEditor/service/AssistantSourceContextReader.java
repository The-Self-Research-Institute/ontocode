package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import self.research.ontology.owlEditor.service.AssistantContextToolService.Item;
import self.research.ontology.owlEditor.service.AssistantContextToolService.Target;
import self.research.ontology.owlEditor.util.IdentifierMentionLines;
import self.research.ontology.owlEditor.util.RdfSourceDiagnostics;
import self.research.ontology.owlEditor.util.RdfXmlSubjectBlockReader;
import self.research.ontology.owlEditor.util.SubjectBlocks;
import self.research.ontology.owlEditor.util.TurtleSubjectBlockReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

class AssistantSourceContextReader {

    static final String DEFAULT_FORMAT = "turtle";
    static final int MAX_DIAGNOSTICS = 50;
    static final int MAX_MENTION_LINES = 10_000;
    static final int MAX_RANGE_LINES = 2_000;
    static final SubjectBlocks.Limits STATEMENT_LIMITS = new SubjectBlocks.Limits(10, 500, 256 * 1024);

    private static final Set<String> FORMAT_NAMES = Set.of("turtle", "ttl", "ntriples", "nt", "rdfxml", "jsonld",
            "owlxml", "manchester", "manchestersyntax", "functional", "functionalsyntax", "obo");
    private static final Set<String> OWL_API_FORMATS = Set.of("owlxml", "manchester", "manchestersyntax",
            "functional", "functionalsyntax", "obo");

    record SourceRead(List<Item> items, boolean partial) {}

    record RangeSpec(String format, long startLine, int lineCount, boolean clamped) {
        boolean contains(long zeroBasedLine) {
            return zeroBasedLine >= startLine && zeroBasedLine < startLine + lineCount;
        }
    }

    record FormatAndValue(String format, String value) {}

    private record LineFilter(LongPredicate accepts, LongPredicate extendsBeyond) {}

    private final StorageManager storageManager;
    private final Duration diagnosticsTimeout;

    AssistantSourceContextReader(StorageManager storageManager, Duration diagnosticsTimeout) {
        this.storageManager = storageManager;
        this.diagnosticsTimeout = diagnosticsTimeout;
    }

    static RangeSpec parseRange(String encodedRange) {
        String[] formatAndRange = encodedRange.split(":", 2);
        String format = formatAndRange.length > 1 ? formatAndRange[0] : DEFAULT_FORMAT;
        String[] bounds = (formatAndRange.length > 1 ? formatAndRange[1] : formatAndRange[0]).split("-", 2);
        long startLine = Long.parseLong(bounds[0].trim());
        int lineCount = bounds.length > 1 ? Integer.parseInt(bounds[1].trim()) : 50;
        if (startLine < 0 || lineCount < 1) {
            throw new IllegalArgumentException("Range must be <format>:<startLine>-<lineCount> with startLine >= 0 "
                    + "and lineCount >= 1");
        }
        boolean clamped = lineCount > MAX_RANGE_LINES;
        return new RangeSpec(format, startLine, Math.min(lineCount, MAX_RANGE_LINES), clamped);
    }

    static FormatAndValue splitFormat(String value) {
        String trimmed = value == null ? "" : value.trim();
        int colon = trimmed.indexOf(':');
        if (colon > 0 && colon + 1 < trimmed.length()
                && FORMAT_NAMES.contains(trimmed.substring(0, colon).toLowerCase(Locale.ROOT))) {
            return new FormatAndValue(trimmed.substring(0, colon).toLowerCase(Locale.ROOT),
                    trimmed.substring(colon + 1).trim());
        }
        return new FormatAndValue(DEFAULT_FORMAT, trimmed);
    }

    SourceRead statements(String projectId, String value) throws IOException {
        FormatAndValue target = splitFormat(value);
        String format = target.format();
        boolean turtleLike = format.equals("turtle") || format.equals("ttl") || format.equals("ntriples")
                || format.equals("nt");
        if (!turtleLike && !format.equals("rdfxml")) {
            return new SourceRead(List.of(note(format, "Statement blocks can only be read from the turtle or rdfxml "
                    + "source, not " + format + ". Ask for turtle:" + target.value() + " instead.")), true);
        }
        Path file = storageManager.ensureCodeViewFile(projectId, format);
        SubjectBlocks blocks;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            blocks = turtleLike
                    ? TurtleSubjectBlockReader.read(reader, target.value(), STATEMENT_LIMITS)
                    : RdfXmlSubjectBlockReader.read(reader, target.value(), STATEMENT_LIMITS);
        }
        List<Item> items = new ArrayList<>();
        boolean partial = blocks.moreBlocks();
        for (SubjectBlocks.Block block : blocks.blocks()) {
            items.add(Item.builder().source(format).range(block.startLine() + "-" + block.lineCount())
                    .text(block.text()).kind("statement").build());
            if (block.truncated()) {
                partial = true;
                items.add(note(format, "The statement block starting at line " + block.startLine()
                        + " is longer than the " + STATEMENT_LIMITS.maxLinesPerBlock() + "-line read cap, so only its "
                        + "first " + block.lineCount() + " lines are shown. Read the rest with a range target."));
            }
        }
        if (blocks.moreBlocks()) {
            items.add(note(format, target.value() + " is the subject of more than " + STATEMENT_LIMITS.maxBlocks()
                    + " statement blocks; only the first " + STATEMENT_LIMITS.maxBlocks() + " are shown."));
        }
        if (blocks.blocks().isEmpty()) {
            items.add(note(format, "No statement block in the " + format + " source has " + target.value()
                    + " as its subject."));
        }
        return new SourceRead(items, partial);
    }

    SourceRead diagnostics(String projectId, List<Target> targets) throws IOException {
        Map<String, List<LineFilter>> filtersByFormat = new LinkedHashMap<>();
        List<Item> items = new ArrayList<>();
        boolean partial = false;
        for (Target target : targets) {
            if ("range".equals(target.type())) {
                RangeSpec range = parseRange(target.value());
                filtersByFormat.computeIfAbsent(range.format().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(new LineFilter(line -> line > 0 && range.contains(line - 1),
                                fatal -> range.startLine() + range.lineCount() > fatal));
            } else {
                FormatAndValue identifier = splitFormat(target.value());
                if (rdfFormatFor(identifier.format()) == null) {
                    filtersByFormat.computeIfAbsent(identifier.format(), k -> new ArrayList<>());
                    continue;
                }
                IdentifierMentionLines.Result mentions = mentionLines(projectId, identifier);
                if (mentions.capped()) {
                    partial = true;
                    items.add(note(identifier.format(), identifier.value() + " is mentioned on more than "
                            + MAX_MENTION_LINES + " lines; diagnostics cover only the first " + MAX_MENTION_LINES + "."));
                }
                Set<Long> lines = mentions.lines();
                filtersByFormat.computeIfAbsent(identifier.format(), k -> new ArrayList<>())
                        .add(new LineFilter(lines::contains, fatal -> lines.stream().anyMatch(line -> line > fatal)));
            }
        }
        if (targets.isEmpty()) {
            filtersByFormat.put(DEFAULT_FORMAT, List.of(new LineFilter(line -> true, fatal -> true)));
        }
        for (Map.Entry<String, List<LineFilter>> entry : filtersByFormat.entrySet()) {
            partial |= diagnoseFormat(projectId, entry.getKey(), entry.getValue(), items);
        }
        return new SourceRead(items, partial);
    }

    private IdentifierMentionLines.Result mentionLines(String projectId, FormatAndValue identifier)
            throws IOException {
        Path file = storageManager.ensureCodeViewFile(projectId, identifier.format());
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return IdentifierMentionLines.scan(reader, identifier.value(),
                    rdfFormatFor(identifier.format()) == RDFFormat.RDFXML, MAX_MENTION_LINES);
        }
    }

    private boolean diagnoseFormat(String projectId, String format, List<LineFilter> filters, List<Item> items)
            throws IOException {
        RDFFormat rdfFormat = rdfFormatFor(format);
        if (rdfFormat == null) {
            String why = OWL_API_FORMATS.contains(format)
                    ? "The " + format + " source is an OWL API format, which this parser cannot check line by line."
                    : "Unknown source format " + format + ".";
            items.add(note(format, why + " Diagnostics are available for turtle, ntriples and rdfxml; ask for "
                    + "turtle instead."));
            return true;
        }
        if (filters.isEmpty()) {
            return false;
        }
        LongPredicate union = line -> filters.stream().anyMatch(filter -> filter.accepts().test(line));
        Path file = storageManager.ensureCodeViewFile(projectId, format);
        RdfSourceDiagnostics.Result result =
                RdfSourceDiagnostics.collect(file, rdfFormat, union, MAX_DIAGNOSTICS, diagnosticsTimeout);
        boolean fatalShown = false;
        for (RdfSourceDiagnostics.Issue issue : result.issues()) {
            boolean fatal = issue.level() == RdfSourceDiagnostics.Level.FATAL;
            fatalShown |= fatal;
            String text = (issue.level() == RdfSourceDiagnostics.Level.WARNING ? "WARNING: " : "ERROR: ")
                    + issue.message()
                    + (fatal ? " Parsing stopped here, so nothing after this point was checked." : "");
            items.add(Item.builder().source(format).range(issue.line() > 0 ? (issue.line() - 1) + "-1" : null)
                    .text(text).kind("diagnostic").build());
        }
        if (result.capped()) {
            items.add(note(format, "Only the first " + MAX_DIAGNOSTICS + " issues are shown; there are more."));
        }
        if (result.timedOut()) {
            items.add(note(format, "The document took too long to check, so only its first part was checked."));
        }
        long fatalLine = result.fatalLine();
        boolean stopMatters = result.stoppedEarly()
                && (fatalLine < 0 || filters.stream().anyMatch(filter -> filter.extendsBeyond().test(fatalLine)));
        if (stopMatters && !fatalShown) {
            items.add(note(format, "Parsing stopped early on an error"
                    + (fatalLine > 0 ? " at line " + (fatalLine - 1) : "")
                    + " outside the requested lines, so later lines were not checked."));
        }
        return result.capped() || result.timedOut() || stopMatters;
    }

    static RDFFormat rdfFormatFor(String format) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "turtle", "ttl" -> RDFFormat.TURTLE;
            case "ntriples", "nt" -> RDFFormat.NTRIPLES;
            case "rdfxml", "xml" -> RDFFormat.RDFXML;
            case "jsonld" -> RDFFormat.JSONLD;
            default -> null;
        };
    }

    private static Item note(String format, String text) {
        return Item.builder().source(format).text("NOTE: " + text).kind("note").build();
    }
}
