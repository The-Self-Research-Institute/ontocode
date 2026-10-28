package self.research.ontology.owlEditor.service.spreadsheet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import self.research.ontology.owlEditor.service.spreadsheet.RuleImportFile.RejectedException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class RuleSpreadsheet {

    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_ROWS = 500;
    public static final int MAX_NAME_CHARS = 200;
    public static final int MAX_RULE_CHARS = 5_000;
    public static final int MAX_COMMENT_CHARS = 2_000;
    public static final int MAX_CATEGORY_CHARS = 200;
    static final long MAX_UNPACKED_BYTES = 50L * 1024 * 1024;
    static final int MAX_ZIP_ENTRIES = 2_000;
    static final int MAX_INFLATE_RATIO = 100;
    public static final String SHEET_RULES = "Rules";
    public static final String SHEET_PREFIXES = "Prefixes";
    public static final String SHEET_HELP = "How to use";
    public static final String DEFAULT_LABEL = "(default)";
    private static final Set<String> KNOWN = Set.of("name", "rule", "comment", "category", "enabled");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(MAX_UNPACKED_BYTES);
    }

    private RuleSpreadsheet() {
    }

    public record ExportRule(String name, String text, String comment, String category, boolean enabled) {
    }

    private static RejectedException reject(String message) {
        return new RejectedException(400, message);
    }

    private static RejectedException notXlsx() {
        return reject("This is not a valid .xlsx file");
    }

    public static RuleImportFile read(byte[] data, String fileName) {
        if (data == null || data.length == 0) {
            throw reject("The file is empty");
        }
        if (data.length > MAX_FILE_BYTES) {
            throw new RejectedException(413, "The file is larger than " + (MAX_FILE_BYTES / (1024 * 1024)) + " MB");
        }
        String extension = extensionOf(fileName);
        String hash = sha256(data);
        return switch (extension) {
            case "xlsx" -> readWorkbook(data, fileName, hash);
            case "json" -> readJson(data, fileName, hash);
            case "xlsm", "xlsb" -> throw reject("Macro-enabled workbooks (." + extension
                    + ") are not accepted. Save the file as .xlsx and try again.");
            default -> throw reject("Choose an Excel (.xlsx) or JSON (.json) file");
        };
    }

    private static String extensionOf(String fileName) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isZip(byte[] data) {
        return data.length > 3 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4;
    }

    private static boolean isOle(byte[] data) {
        return data.length > 7 && (data[0] & 0xFF) == 0xD0 && (data[1] & 0xFF) == 0xCF && (data[2] & 0xFF) == 0x11
                && (data[3] & 0xFF) == 0xE0;
    }

    private static RuleImportFile readWorkbook(byte[] data, String fileName, String hash) {
        if (isOle(data)) {
            throw reject("This workbook is password protected or in an old Excel format. Save it as a plain .xlsx file and try again.");
        }
        if (!isZip(data)) {
            throw notXlsx();
        }
        inspectZip(data);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Sheet rules = sheetNamed(workbook, SHEET_RULES);
            if (rules == null) {
                rules = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            }
            if (rules == null) {
                throw notXlsx();
            }
            List<RuleImportFile.Row> rows = readRules(rules, formatter);
            Sheet prefixes = sheetNamed(workbook, SHEET_PREFIXES);
            Map<String, String> declared = prefixes == null ? Map.of() : readPrefixes(prefixes, formatter);
            return new RuleImportFile(fileName, hash, rows, declared);
        } catch (RejectedException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw notXlsx();
        }
    }

    private static void inspectZip(byte[] data) {
        boolean contentTypes = false;
        long total = 0;
        int entries = 0;
        byte[] buffer = new byte[16 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw unsafe();
                }
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("xl/externallinks/")) {
                    throw reject("Workbooks that link to other files are not accepted. Paste the values and try again.");
                }
                if (name.contains("vbaproject")) {
                    throw macros();
                }
                boolean types = "[content_types].xml".equals(name);
                contentTypes |= types;
                ByteArrayOutputStream kept = types ? new ByteArrayOutputStream() : null;
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_UNPACKED_BYTES) {
                        throw unsafe();
                    }
                    if (kept != null && kept.size() < 1_000_000) {
                        kept.write(buffer, 0, read);
                    }
                }
                if (kept != null && kept.toString(StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("macroenabled")) {
                    throw macros();
                }
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof RejectedException rejected) {
                throw rejected;
            }
            throw notXlsx();
        }
        if (!contentTypes) {
            throw notXlsx();
        }
        if (total > 1_000_000 && total / Math.max(1, data.length) > MAX_INFLATE_RATIO) {
            throw unsafe();
        }
    }

    private static RejectedException macros() {
        return reject("Macro-enabled workbooks are not accepted. Save the file as a plain .xlsx and try again.");
    }

    private static RejectedException unsafe() {
        return reject("This file expands to far more data than a rules file can hold, so it was not opened");
    }

    private static Sheet sheetNamed(Workbook workbook, String name) {
        for (Sheet sheet : workbook) {
            if (sheet.getSheetName().trim().equalsIgnoreCase(name)) {
                return sheet;
            }
        }
        return null;
    }

    private static String text(Cell cell, DataFormatter formatter) {
        return cell == null ? "" : formatter.formatCellValue(cell);
    }

    private static boolean formula(Cell cell) {
        return cell != null && cell.getCellType() == CellType.FORMULA;
    }

    private static int headerRow(Sheet sheet, DataFormatter formatter) {
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            if (!blank(sheet.getRow(r), formatter)) {
                return r;
            }
        }
        return -1;
    }

    private static boolean blank(Row row, DataFormatter formatter) {
        if (row == null) {
            return true;
        }
        for (Cell cell : row) {
            if (formula(cell) || !text(cell, formatter).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Integer> columns(Row header, DataFormatter formatter, Set<String> unique, String sheet,
                                                String needs) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (Cell cell : header) {
            String title = text(cell, formatter).trim().toLowerCase(Locale.ROOT);
            if (title.isEmpty()) {
                continue;
            }
            if (columns.containsKey(title) && unique.contains(title)) {
                throw reject("The " + sheet + " sheet has the column \"" + text(cell, formatter).trim() + "\" more than once");
            }
            columns.putIfAbsent(title, cell.getColumnIndex());
        }
        for (String required : unique) {
            if (!columns.containsKey(required)) {
                throw reject(needs);
            }
        }
        return columns;
    }

    private static String cellText(Row row, Map<String, Integer> columns, String column, DataFormatter formatter) {
        Integer index = columns.get(column);
        return index == null ? "" : text(row.getCell(index), formatter);
    }

    private static List<RuleImportFile.Row> readRules(Sheet sheet, DataFormatter formatter) {
        int headerIndex = headerRow(sheet, formatter);
        if (headerIndex < 0) {
            throw reject("The Rules sheet is empty. The first row must have the columns Name and Rule");
        }
        Map<String, Integer> columns = columns(sheet.getRow(headerIndex), formatter, Set.of("name", "rule"), SHEET_RULES,
                "The first row of the Rules sheet must have the columns Name and Rule");
        List<RuleImportFile.Row> rows = new ArrayList<>();
        for (int r = headerIndex + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (blank(row, formatter)) {
                continue;
            }
            if (rows.size() >= MAX_ROWS) {
                throw reject("A file can hold at most " + MAX_ROWS + " rules. Split it and import the parts one by one");
            }
            rows.add(ruleRow(row, r + 1, columns, formatter));
        }
        return rows;
    }

    private static RuleImportFile.Row ruleRow(Row row, int number, Map<String, Integer> columns, DataFormatter formatter) {
        for (String column : KNOWN) {
            Integer index = columns.get(column);
            if (index != null && formula(row.getCell(index))) {
                String cell = row.getCell(index).getAddress().formatAsString();
                return problem(RuleImportFile.KIND_FORMULA, number, "Cell " + cell
                        + " is a formula. Formula cells are not accepted, paste the values instead",
                        cellText(row, columns, "name", formatter));
            }
        }
        String name = cellText(row, columns, "name", formatter).trim();
        String rule = cellText(row, columns, "rule", formatter).trim();
        String comment = cellText(row, columns, "comment", formatter).trim();
        String category = cellText(row, columns, "category", formatter).trim();
        return checked(SHEET_RULES, number, name, rule, comment, category, cellText(row, columns, "enabled", formatter));
    }

    private static RuleImportFile.Row problem(String kind, int number, String message, String name) {
        return new RuleImportFile.Row(SHEET_RULES, number, name, null, null, null, true, kind, message);
    }

    private static RuleImportFile.Row checked(String sheet, int number, String name, String rule, String comment,
                                              String category, String enabledText) {
        String tooLong = name.length() > MAX_NAME_CHARS ? "The name is longer than " + MAX_NAME_CHARS + " characters"
                : rule.length() > MAX_RULE_CHARS ? "The rule text is longer than " + MAX_RULE_CHARS + " characters"
                : comment.length() > MAX_COMMENT_CHARS ? "The comment is longer than " + MAX_COMMENT_CHARS + " characters"
                : category.length() > MAX_CATEGORY_CHARS ? "The category is longer than " + MAX_CATEGORY_CHARS + " characters"
                : null;
        String shown = name.length() > MAX_NAME_CHARS ? name.substring(0, MAX_NAME_CHARS) : name;
        if (tooLong != null) {
            return new RuleImportFile.Row(sheet, number, shown, null, null, null, true, RuleImportFile.KIND_INVALID, tooLong);
        }
        Boolean enabled = enabledOf(enabledText);
        if (enabled == null) {
            return new RuleImportFile.Row(sheet, number, name, rule, comment, category, true,
                    RuleImportFile.KIND_BAD_ENABLED,
                    "Enabled must be yes or no (found \"" + enabledText.trim() + "\")");
        }
        return new RuleImportFile.Row(sheet, number, name, rule, comment, category, enabled, null, null);
    }

    static Boolean enabledOf(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "", "yes", "true", "y", "1", "enabled" -> Boolean.TRUE;
            case "no", "false", "n", "0", "disabled" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static Map<String, String> readPrefixes(Sheet sheet, DataFormatter formatter) {
        int headerIndex = headerRow(sheet, formatter);
        if (headerIndex < 0) {
            return Map.of();
        }
        Map<String, Integer> columns = columns(sheet.getRow(headerIndex), formatter, Set.of("prefix", "namespace"),
                SHEET_PREFIXES, "The Prefixes sheet needs the columns prefix and namespace");
        Map<String, String> prefixes = new LinkedHashMap<>();
        for (int r = headerIndex + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (blank(row, formatter)) {
                continue;
            }
            Cell prefixCell = row.getCell(columns.get("prefix"));
            Cell namespaceCell = row.getCell(columns.get("namespace"));
            if (formula(prefixCell) || formula(namespaceCell)) {
                throw reject("The Prefixes sheet has a formula in row " + (r + 1) + ". Paste the values instead");
            }
            String prefix = prefixKey(text(prefixCell, formatter));
            String namespace = text(namespaceCell, formatter).trim();
            if (namespace.isEmpty()) {
                throw reject("Row " + (r + 1) + " of the Prefixes sheet has no namespace");
            }
            String earlier = prefixes.putIfAbsent(prefix, namespace);
            if (earlier != null && !earlier.equals(namespace)) {
                throw reject("The Prefixes sheet gives \"" + (prefix.isEmpty() ? DEFAULT_LABEL : prefix)
                        + "\" two different namespaces");
            }
        }
        return prefixes;
    }

    private static String prefixKey(String raw) {
        String prefix = raw == null ? "" : raw.trim();
        if (prefix.endsWith(":")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return DEFAULT_LABEL.equalsIgnoreCase(prefix) ? "" : prefix;
    }

    private static RuleImportFile readJson(byte[] data, String fileName, String hash) {
        if (isZip(data) || isOle(data)) {
            throw reject("This is not a valid JSON file");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(data);
        } catch (IOException | RuntimeException e) {
            throw reject("This is not a valid JSON file");
        }
        if (root == null || root.isMissingNode()) {
            throw reject("This is not a valid JSON file");
        }
        JsonNode list = root.isArray() ? root : root.path("rules");
        if (!list.isArray()) {
            throw reject("The JSON file must be a list of rules");
        }
        if (list.size() > MAX_ROWS) {
            throw reject("A file can hold at most " + MAX_ROWS + " rules. Split it and import the parts one by one");
        }
        Map<String, String> prefixes = new LinkedHashMap<>();
        JsonNode declared = root.isObject() ? root.path("prefixes") : null;
        if (declared != null && declared.isObject()) {
            declared.fields().forEachRemaining(entry -> prefixes.put(prefixKey(entry.getKey()), entry.getValue().asText()));
        }
        List<RuleImportFile.Row> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            rows.add(jsonRow(list.get(i), i + 1));
        }
        return new RuleImportFile(fileName, hash, rows, prefixes);
    }

    private static String field(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isValueNode() && !value.isNull()) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private static RuleImportFile.Row jsonRow(JsonNode node, int number) {
        if (node == null || !node.isObject()) {
            return new RuleImportFile.Row("json", number, "", null, null, null, true, RuleImportFile.KIND_INVALID,
                    "This entry is not a rule object");
        }
        JsonNode enabled = node.path("enabled");
        String enabledText = enabled.isMissingNode() || enabled.isNull() ? "" : enabled.isValueNode() ? enabled.asText() : "?";
        return checked("json", number, field(node, "ruleName", "name"), field(node, "ruleText", "rule"),
                field(node, "comment"), field(node, "category"), enabledText);
    }

    public static byte[] writeJson(List<ExportRule> rules, Map<String, String> prefixes) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ExportRule rule : rules) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleName", rule.name());
            item.put("ruleText", rule.text());
            item.put("comment", rule.comment() == null ? "" : rule.comment());
            item.put("category", rule.category() == null ? "" : rule.category());
            item.put("enabled", rule.enabled());
            list.add(item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, String> shown = new LinkedHashMap<>();
        prefixes.forEach((prefix, namespace) -> shown.put(prefix.isEmpty() ? DEFAULT_LABEL : prefix, namespace));
        root.put("prefixes", shown);
        root.put("rules", list);
        try {
            return MAPPER.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(root);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] writeXlsx(List<ExportRule> rules, Map<String, String> prefixes) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(workbook);
            CellStyle literal = workbook.createCellStyle();
            literal.setQuotePrefixed(true);
            Sheet sheet = workbook.createSheet(SHEET_RULES);
            row(sheet, 0, header, literal, "Name", "Rule", "Comment", "Category", "Enabled");
            int r = 1;
            for (ExportRule rule : rules) {
                row(sheet, r++, null, literal, rule.name(), rule.text(), rule.comment(), rule.category(),
                        rule.enabled() ? "yes" : "no");
            }
            for (int c = 0; c < 5; c++) {
                sheet.setColumnWidth(c, c == 1 ? 18_000 : 7_000);
            }
            Sheet notes = workbook.createSheet(SHEET_PREFIXES);
            row(notes, 0, header, literal, "prefix", "namespace");
            int p = 1;
            for (Map.Entry<String, String> entry : prefixes.entrySet()) {
                row(notes, p++, null, literal, entry.getKey().isEmpty() ? DEFAULT_LABEL : entry.getKey(), entry.getValue());
            }
            notes.setColumnWidth(0, 6_000);
            notes.setColumnWidth(1, 20_000);
            Sheet help = workbook.createSheet(SHEET_HELP);
            int h = 0;
            for (String line : helpLines()) {
                row(help, h++, null, literal, line);
            }
            help.setColumnWidth(0, 30_000);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] template() {
        return writeXlsx(List.of(), Map.of());
    }

    private static List<String> helpLines() {
        return List.of(
                "Fill the Rules sheet, one rule per row, then import the file from the SWRL rule editor.",
                "Name: a unique title for the rule. Required.",
                "Rule: the rule text, for example  Person(?x) ^ hasAge(?x, ?a) -> Adult(?x).  Required.",
                "Comment and Category: optional notes.",
                "Enabled: yes or no (also true/false, y/n, 1/0, enabled/disabled). Empty means yes.",
                "Paste values, not formulas. Formula cells are rejected.",
                "The Prefixes sheet lists the prefixes the rules use. It is only used to read this file and never changes the project.",
                "A file can hold up to 500 rules and 5 MB. Rows with problems are skipped and listed in the result.");
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static boolean looksLikeFormula(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }

    private static void row(Sheet sheet, int index, CellStyle style, CellStyle literal, String... values) {
        Row row = sheet.createRow(index);
        for (int c = 0; c < values.length; c++) {
            String value = values[c];
            if (value == null) {
                continue;
            }
            Cell cell = row.createCell(c, CellType.STRING);
            cell.setCellValue(value);
            if (style != null) {
                cell.setCellStyle(style);
            } else if (looksLikeFormula(value)) {
                cell.setCellStyle(literal);
            }
        }
    }
}
