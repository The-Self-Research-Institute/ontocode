package self.research.ontology.owlEditor.service.spreadsheet;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpreadsheetParserServiceTest {

    private final SpreadsheetParserService parser = new SpreadsheetParserService();

    @Test
    void parsesXlsxWithNormalisedCellValues() throws IOException {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("Readings");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("SensorID");
            header.createCell(1).setCellValue("MeasurementValue");
            header.createCell(2).setCellValue("ReadingDate");
            header.createCell(3).setCellValue("Active");

            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("S001");
            row.createCell(1).setCellValue(23.7);
            row.createCell(2).setCellValue(LocalDateTime.of(2026, 9, 2, 10, 1));
            row.getCell(2).setCellStyle(dateStyle);
            row.createCell(3).setCellValue(true);

            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("S002");
            row2.createCell(1).setCellValue(27.0);
            sheet.createRow(5);
            wb.write(out);
            xlsx = out.toByteArray();
        }

        SpreadsheetWorkbook workbook = parser.parse(new ByteArrayInputStream(xlsx), "sensor-readings.xlsx");

        assertEquals("xlsx", workbook.format());
        SpreadsheetWorkbook.Sheet sheet = workbook.sheet("Readings");
        assertEquals(3, sheet.rowCount());
        assertEquals(4, sheet.columnCount());
        assertEquals("S001", sheet.cell(2, 1));
        assertEquals("23.7", sheet.cell(2, 2));
        assertEquals("2026-09-02T10:01:00", sheet.cell(2, 3));
        assertEquals("true", sheet.cell(2, 4));
        assertEquals("27", sheet.cell(3, 2));
        assertEquals("", sheet.cell(3, 4), "short rows are padded");
        assertEquals("", sheet.cell(99, 1), "out of range is empty");
    }

    @Test
    void parsesCsvWithBomQuotesAndSemicolons() {
        String csv = "\uFEFFSensorID;Label\r\nS001;\"Reading; first\"\r\nS002;Second\r\n\r\n";
        SpreadsheetWorkbook workbook = parser.parse(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "C:\\data\\sensor-readings.csv");

        SpreadsheetWorkbook.Sheet sheet = workbook.sheets().get(0);
        assertEquals("sensor-readings", sheet.name());
        assertEquals(3, sheet.rowCount());
        assertEquals(List.of("SensorID", "Label"), sheet.rows().get(0));
        assertEquals("Reading; first", sheet.cell(2, 2));
    }

    @Test
    void rejectsUnsupportedAndCorruptFiles() {
        byte[] junk = "not a workbook".getBytes(StandardCharsets.UTF_8);
        assertThrows(SpreadsheetParseException.class,
            () -> parser.parse(new ByteArrayInputStream(junk), "ontology.owl"));
        assertThrows(SpreadsheetParseException.class,
            () -> parser.parse(new ByteArrayInputStream(junk), "broken.xlsx"));
    }

    @Test
    void rejectsFilesOverTheSizeLimit() {
        byte[] big = new byte[SpreadsheetParserService.MAX_FILE_BYTES + 1];
        SpreadsheetParseException e = assertThrows(SpreadsheetParseException.class,
            () -> parser.parse(new ByteArrayInputStream(big), "huge.xlsx"));
        assertEquals("'huge.xlsx' is larger than 2 MB. Split it into smaller files and import each one.",
            e.getMessage());
    }

    @Test
    void acceptsAFileExactlyAtTheSizeLimit() {
        byte[] csv = new byte[SpreadsheetParserService.MAX_FILE_BYTES];
        java.util.Arrays.fill(csv, (byte) 'a');
        SpreadsheetWorkbook workbook = parser.parse(new ByteArrayInputStream(csv), "edge.csv");
        assertEquals(1, workbook.sheets().get(0).rowCount());
    }

    @Test
    void convertsColumnLetters() {
        assertEquals("A", SpreadsheetParserService.columnLetter(1));
        assertEquals("Z", SpreadsheetParserService.columnLetter(26));
        assertEquals("AA", SpreadsheetParserService.columnLetter(27));
        assertEquals(27, SpreadsheetParserService.columnIndex("aa"));
        assertEquals(-1, SpreadsheetParserService.columnIndex("A1"));
    }
}
