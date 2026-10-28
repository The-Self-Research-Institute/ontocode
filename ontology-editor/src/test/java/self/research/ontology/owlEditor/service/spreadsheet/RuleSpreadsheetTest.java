package self.research.ontology.owlEditor.service.spreadsheet;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.service.spreadsheet.RuleImportFile.RejectedException;
import self.research.ontology.owlEditor.service.spreadsheet.RuleSpreadsheet.ExportRule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleSpreadsheetTest {

    static byte[] book(String[] header, String[][] rows, Consumer<XSSFWorkbook> more) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Rules");
            if (header != null) {
                fill(sheet.createRow(0), header);
            }
            int r = header == null ? 0 : 1;
            for (String[] row : rows) {
                fill(sheet.createRow(r++), row);
            }
            if (more != null) {
                more.accept(workbook);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    static void fill(Row row, String... values) {
        for (int c = 0; c < values.length; c++) {
            if (values[c] != null) {
                row.createCell(c).setCellValue(values[c]);
            }
        }
    }

    static final String[] HEADER = {"Name", "Rule", "Comment", "Category", "Enabled"};

    static RuleImportFile read(byte[] data) {
        return RuleSpreadsheet.read(data, "rules.xlsx");
    }

    static RejectedException rejected(byte[] data, String name) {
        return assertThrows(RejectedException.class, () -> RuleSpreadsheet.read(data, name));
    }

    static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void headersAreCaseInsensitiveTrimmedAndExtraColumnsAndBlankRowsAreIgnored() throws Exception {
        byte[] data = book(new String[] {" NAME ", "rule", "Notes", "enabled"}, new String[][] {
                {"First", "A>B", "ignored", "no"},
                {null, null},
                {"Second", "C>D", "ignored", null}}, null);

        RuleImportFile file = read(data);

        assertEquals(2, file.rows().size());
        assertEquals("First", file.rows().get(0).name());
        assertEquals(2, file.rows().get(0).row());
        assertEquals(Boolean.FALSE, file.rows().get(0).enabled());
        assertEquals(4, file.rows().get(1).row(), "row numbers are the Excel row numbers");
        assertEquals(Boolean.TRUE, file.rows().get(1).enabled());
        assertEquals("", file.rows().get(1).comment());
    }

    @Test
    void aMissingRequiredHeaderRejectsTheFile() throws Exception {
        RejectedException refused = rejected(book(new String[] {"Name", "Notes"}, new String[][] {{"A", "x"}}, null), "r.xlsx");

        assertEquals(400, refused.status());
        assertTrue(refused.getMessage().contains("Name and Rule"), refused.getMessage());
    }

    @Test
    void aDuplicateRequiredHeaderRejectsTheFile() throws Exception {
        RejectedException refused = rejected(book(new String[] {"Name", "Rule", "name"}, new String[][] {{"A", "x", "y"}}, null), "r.xlsx");

        assertTrue(refused.getMessage().contains("more than once"), refused.getMessage());
    }

    @Test
    void aSheetWithNoHeaderRowIsRejected() throws Exception {
        RejectedException refused = rejected(book(null, new String[][] {}, null), "r.xlsx");

        assertTrue(refused.getMessage().contains("empty"), refused.getMessage());
    }

    @Test
    void theEnabledColumnAcceptsTheDocumentedWordsAndNothingElse() throws Exception {
        String[] yes = {"yes", "TRUE", "y", "1", "Enabled", "", " Yes "};
        String[] no = {"no", "False", "N", "0", "DISABLED"};
        List<String[]> rows = new ArrayList<>();
        for (String value : yes) {
            rows.add(new String[] {"r" + rows.size(), "A>B", null, null, value});
        }
        for (String value : no) {
            rows.add(new String[] {"r" + rows.size(), "A>B", null, null, value});
        }
        rows.add(new String[] {"odd", "A>B", null, null, "maybe"});

        RuleImportFile file = read(book(HEADER, rows.toArray(new String[0][]), null));

        for (int i = 0; i < yes.length; i++) {
            assertEquals(Boolean.TRUE, file.rows().get(i).enabled(), yes[i]);
            assertNull(file.rows().get(i).problemKind());
        }
        for (int i = 0; i < no.length; i++) {
            assertEquals(Boolean.FALSE, file.rows().get(yes.length + i).enabled(), no[i]);
        }
        RuleImportFile.Row odd = file.rows().get(yes.length + no.length);
        assertEquals("bad_enabled", odd.problemKind());
        assertTrue(odd.problem().contains("maybe"));
    }

    @Test
    void realFormulaCellsAreRowErrorsButFormulaLookingTextIsLiteral() throws Exception {
        byte[] data = book(HEADER, new String[][] {
                {"=SUM(A1)", "=A1", "-note", "@who", null},
                {"Plain", "A>B", "+1 comment", "=cat", null}}, workbook -> {
            Row row = workbook.getSheet("Rules").createRow(3);
            row.createCell(0).setCellValue("Computed");
            row.createCell(1).setCellFormula("\"A>B\"");
        });

        RuleImportFile file = read(data);

        assertEquals(3, file.rows().size());
        assertEquals("=SUM(A1)", file.rows().get(0).name());
        assertEquals("=A1", file.rows().get(0).text());
        assertEquals("-note", file.rows().get(0).comment());
        assertEquals("@who", file.rows().get(0).category());
        assertNull(file.rows().get(0).problemKind());
        assertEquals("=cat", file.rows().get(1).category());
        RuleImportFile.Row formula = file.rows().get(2);
        assertEquals("formula_cell", formula.problemKind());
        assertTrue(formula.problem().contains("Formula cells are not accepted"), formula.problem());
        assertEquals(4, formula.row());
    }

    @Test
    void theRowLimitAndTheLengthLimitsAreEnforced() throws Exception {
        String[][] many = new String[501][];
        for (int i = 0; i < many.length; i++) {
            many[i] = new String[] {"n" + i, "A>B"};
        }
        RejectedException tooMany = rejected(book(HEADER, many, null), "r.xlsx");
        assertTrue(tooMany.getMessage().contains("at most 500"), tooMany.getMessage());
        assertEquals(500, read(book(HEADER, java.util.Arrays.copyOf(many, 500), null)).rows().size());

        RuleImportFile file = read(book(HEADER, new String[][] {
                {"n".repeat(201), "A>B"},
                {"ok1", "x".repeat(5001)},
                {"ok2", "A>B", "c".repeat(2001)},
                {"ok3", "x".repeat(5000), "c".repeat(2000)}}, null));
        assertEquals("invalid", file.rows().get(0).problemKind());
        assertEquals("invalid", file.rows().get(1).problemKind());
        assertEquals("invalid", file.rows().get(2).problemKind());
        assertNull(file.rows().get(3).problemKind());
    }

    @Test
    void aFileOverFiveMegabytesIsRejectedWith413() {
        RejectedException refused = rejected(new byte[RuleSpreadsheet.MAX_FILE_BYTES + 1], "big.xlsx");

        assertEquals(413, refused.status());
    }

    @Test
    void anXlsmFileIsRefusedWhateverItContains() throws Exception {
        RejectedException refused = rejected(book(HEADER, new String[][] {{"A", "A>B"}}, null), "rules.xlsm");

        assertTrue(refused.getMessage().contains("Macro-enabled"), refused.getMessage());
    }

    @Test
    void aWorkbookWithMacrosInsideAnXlsxNameIsRefused() throws Exception {
        byte[] valid = book(HEADER, new String[][] {{"A", "A>B"}}, null);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8));
        entries.put("xl/vbaProject.bin", new byte[] {1, 2, 3});

        assertTrue(rejected(zip(entries), "rules.xlsx").getMessage().contains("Macro-enabled"));
        entries.remove("xl/vbaProject.bin");
        entries.put("[Content_Types].xml", "<Types ContentType=\"application/vnd.ms-excel.sheet.macroEnabled.main+xml\"/>"
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(rejected(zip(entries), "rules.xlsx").getMessage().contains("Macro-enabled"));
        assertEquals(1, read(valid).rows().size());
    }

    @Test
    void aWorkbookLinkingToOtherFilesIsRefused() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8));
        entries.put("xl/externalLinks/externalLink1.xml", "<externalLink/>".getBytes(StandardCharsets.UTF_8));

        assertTrue(rejected(zip(entries), "rules.xlsx").getMessage().contains("link to other files"));
    }

    @Test
    void aPasswordProtectedWorkbookIsRefused() {
        byte[] ole = new byte[2048];
        ole[0] = (byte) 0xD0;
        ole[1] = (byte) 0xCF;
        ole[2] = 0x11;
        ole[3] = (byte) 0xE0;

        assertTrue(rejected(ole, "rules.xlsx").getMessage().contains("password"));
    }

    @Test
    void aZipBombIsRefusedBeforeItIsOpened() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8));
        entries.put("xl/worksheets/sheet1.xml", new byte[60 * 1024 * 1024]);
        byte[] bomb = zip(entries);
        assertTrue(bomb.length < RuleSpreadsheet.MAX_FILE_BYTES, "the bomb must be small on disk");

        RejectedException refused = rejected(bomb, "rules.xlsx");

        assertEquals(400, refused.status());
        assertTrue(refused.getMessage().contains("far more data"), refused.getMessage());
    }

    @Test
    void aHighlyCompressedFileIsRefusedEvenUnderTheUnpackedCap() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8));
        entries.put("xl/worksheets/sheet1.xml", new byte[30 * 1024 * 1024]);

        assertTrue(rejected(zip(entries), "rules.xlsx").getMessage().contains("far more data"));
    }

    @Test
    void wrongContentUnderAnXlsxNameIsNotAValidXlsx() throws Exception {
        for (byte[] data : new byte[][] {
                "[{\"ruleName\":\"a\"}]".getBytes(StandardCharsets.UTF_8),
                "just some text".getBytes(StandardCharsets.UTF_8),
                new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0},
                zip(Map.of("hello.txt", new byte[] {1}))}) {
            RejectedException refused = rejected(data, "rules.xlsx");
            assertEquals(400, refused.status());
            assertEquals("This is not a valid .xlsx file", refused.getMessage());
        }
    }

    @Test
    void aTruncatedWorkbookIsNotAValidXlsx() throws Exception {
        byte[] valid = book(HEADER, new String[][] {{"A", "A>B"}}, null);

        RejectedException refused = rejected(java.util.Arrays.copyOf(valid, valid.length / 2), "rules.xlsx");

        assertEquals("This is not a valid .xlsx file", refused.getMessage());
    }

    @Test
    void anUnknownExtensionAndAnEmptyFileAreRefused() {
        assertTrue(rejected(new byte[] {1}, "rules.csv").getMessage().contains(".xlsx"));
        assertTrue(rejected(new byte[0], "rules.xlsx").getMessage().contains("empty"));
    }

    @Test
    void thePrefixesSheetIsReadAndAConflictingDuplicateIsRefused() throws Exception {
        byte[] data = book(HEADER, new String[][] {{"A", "ex:A>ex:B"}}, workbook -> {
            Sheet prefixes = workbook.createSheet("Prefixes");
            fill(prefixes.createRow(0), "prefix", "namespace");
            fill(prefixes.createRow(1), "ex:", "http://example.org/ns#");
            fill(prefixes.createRow(2), "(default)", "http://example.org/default#");
        });

        RuleImportFile file = read(data);

        assertEquals("http://example.org/ns#", file.prefixes().get("ex"));
        assertEquals("http://example.org/default#", file.prefixes().get(""));

        byte[] clash = book(HEADER, new String[][] {{"A", "x"}}, workbook -> {
            Sheet prefixes = workbook.createSheet("Prefixes");
            fill(prefixes.createRow(0), "prefix", "namespace");
            fill(prefixes.createRow(1), "ex", "http://one/");
            fill(prefixes.createRow(2), "ex", "http://two/");
        });
        assertTrue(rejected(clash, "r.xlsx").getMessage().contains("two different namespaces"));
    }

    @Test
    void jsonRulesUseTheSameReportShapeWithRowEqualToIndexPlusOne() {
        String json = "{\"prefixes\":{\"ex\":\"http://e/\"},\"rules\":[{\"ruleName\":\"A\",\"ruleText\":\"X>Y\",\"enabled\":false},"
                + "{\"name\":\"B\",\"rule\":\"X>Z\",\"enabled\":\"maybe\"},42,{\"ruleName\":\"C\",\"ruleText\":\"X>Y\"}]}";

        RuleImportFile file = RuleSpreadsheet.read(json.getBytes(StandardCharsets.UTF_8), "rules.json");

        assertEquals(4, file.rows().size());
        assertEquals("json", file.rows().get(0).sheet());
        assertEquals(1, file.rows().get(0).row());
        assertEquals(Boolean.FALSE, file.rows().get(0).enabled());
        assertEquals("bad_enabled", file.rows().get(1).problemKind());
        assertEquals(2, file.rows().get(1).row());
        assertEquals("invalid", file.rows().get(2).problemKind());
        assertEquals(Boolean.TRUE, file.rows().get(3).enabled());
        assertEquals("http://e/", file.prefixes().get("ex"));
    }

    @Test
    void badJsonIsRefused() throws Exception {
        assertEquals("This is not a valid JSON file", rejected("{nope".getBytes(StandardCharsets.UTF_8), "r.json").getMessage());
        assertEquals("This is not a valid JSON file", rejected(book(HEADER, new String[][] {}, null), "r.json").getMessage());
        assertTrue(rejected("{\"a\":1}".getBytes(StandardCharsets.UTF_8), "r.json").getMessage().contains("list of rules"));
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 501; i++) {
            many.append(i == 0 ? "" : ",").append("{}");
        }
        assertTrue(rejected(many.append("]").toString().getBytes(StandardCharsets.UTF_8), "r.json").getMessage().contains("at most 500"));
    }

    @Test
    void exportThenImportKeepsRulesEnabledFlagsAndPrefixes() throws Exception {
        List<ExportRule> rules = List.of(
                new ExportRule("Students are people", "ex:Student(?x) -> ex:Person(?x)", "every student", "People", true),
                new ExportRule("Off", "ex:Teacher(?x) -> ex:Person(?x)", "", "", false),
                new ExportRule("=evil()", "=1+1", "-minus", "@at", true));
        Map<String, String> prefixes = new LinkedHashMap<>();
        prefixes.put("", "http://example.org/o#");
        prefixes.put("ex", "http://example.org/ns#");

        RuleImportFile file = read(RuleSpreadsheet.writeXlsx(rules, prefixes));

        assertEquals(3, file.rows().size());
        for (int i = 0; i < rules.size(); i++) {
            RuleImportFile.Row row = file.rows().get(i);
            assertEquals(rules.get(i).name(), row.name());
            assertEquals(rules.get(i).text(), row.text());
            assertEquals(rules.get(i).comment(), row.comment());
            assertEquals(rules.get(i).category(), row.category());
            assertEquals(rules.get(i).enabled(), row.enabled());
            assertNull(row.problemKind());
        }
        assertEquals(prefixes, file.prefixes());
    }

    @Test
    void exportWritesFormulaLookingTextAsQuotedStringsExcelNeverEvaluates() throws Exception {
        byte[] data = RuleSpreadsheet.writeXlsx(List.of(new ExportRule("=cmd|' /C calc'!A0", "+SUM(1)", "-1", "@x", true)), Map.of());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            Row row = workbook.getSheet("Rules").getRow(1);
            for (int c = 0; c < 4; c++) {
                Cell cell = row.getCell(c);
                assertEquals(org.apache.poi.ss.usermodel.CellType.STRING, cell.getCellType());
                assertTrue(cell.getCellStyle().getQuotePrefixed(), "cell " + c);
            }
            assertFalse(row.getCell(4).getCellStyle().getQuotePrefixed());
            assertEquals(List.of("Rules", "Prefixes", "How to use"),
                    java.util.stream.StreamSupport.stream(workbook.spliterator(), false).map(Sheet::getSheetName).toList());
        }
    }

    @Test
    void theTemplateIsAnEmptyRulesSheetWithTheHeadersAndAHelpSheet() throws Exception {
        RuleImportFile file = read(RuleSpreadsheet.template());

        assertTrue(file.rows().isEmpty());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(RuleSpreadsheet.template()))) {
            assertEquals("Name", workbook.getSheet("Rules").getRow(0).getCell(0).getStringCellValue());
            assertEquals("Enabled", workbook.getSheet("Rules").getRow(0).getCell(4).getStringCellValue());
            assertTrue(workbook.getSheet("How to use").getLastRowNum() > 3);
        }
    }

    @Test
    void jsonExportRoundTrips() {
        List<ExportRule> rules = List.of(new ExportRule("A", "X>Y", "c", "cat", false), new ExportRule("B", "X>Z", "", "", true));

        RuleImportFile file = RuleSpreadsheet.read(RuleSpreadsheet.writeJson(rules, Map.of("ex", "http://e/")), "rules.json");

        assertEquals(2, file.rows().size());
        assertEquals(Boolean.FALSE, file.rows().get(0).enabled());
        assertEquals("cat", file.rows().get(0).category());
        assertEquals("http://e/", file.prefixes().get("ex"));
    }
}
