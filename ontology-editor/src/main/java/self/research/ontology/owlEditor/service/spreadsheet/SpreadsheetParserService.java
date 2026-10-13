package self.research.ontology.owlEditor.service.spreadsheet;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class SpreadsheetParserService {

    public static final int MAX_ROWS_PER_SHEET = 100_000;
    public static final int MAX_COLUMNS = 1_000;
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    static final long MAX_UNPACKED_PART_BYTES = 50L * 1024 * 1024;

    static {
        ZipSecureFile.setMaxEntrySize(MAX_UNPACKED_PART_BYTES);
    }

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    public SpreadsheetWorkbook parse(InputStream file, String fileName) {
        String format = formatOf(fileName);
        try {
            InputStream in = withinSizeLimit(file, fileName);
            return switch (format) {
                case "csv" -> parseCsv(in, fileName);
                case "xlsx", "xls" -> parseExcel(in, fileName, format);
                default -> throw new SpreadsheetParseException(
                    "Unsupported file type '" + fileName + "'. Upload an .xlsx, .xls or .csv file.");
            };
        } catch (IOException e) {
            throw new SpreadsheetParseException("Could not read '" + fileName + "': " + e.getMessage(), e);
        }
    }

    private static InputStream withinSizeLimit(InputStream in, String fileName) throws IOException {
        byte[] data = in.readNBytes(MAX_FILE_BYTES + 1);
        if (data.length > MAX_FILE_BYTES) {
            throw new SpreadsheetParseException("'" + fileName + "' is larger than "
                + (MAX_FILE_BYTES / (1024 * 1024)) + " MB. Split it into smaller files and import each one.");
        }
        return new ByteArrayInputStream(data);
    }

    static String formatOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private SpreadsheetWorkbook parseExcel(InputStream in, String fileName, String format) throws IOException {
        Workbook workbook;
        try {
            workbook = WorkbookFactory.create(in);
        } catch (IOException | RuntimeException e) {
            throw new SpreadsheetParseException("'" + fileName + "' is not a valid Excel workbook.", e);
        }
        try (workbook) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<SpreadsheetWorkbook.Sheet> sheets = new ArrayList<>();
            for (org.apache.poi.ss.usermodel.Sheet sheet : workbook) {
                if (sheet.getLastRowNum() + 1 > MAX_ROWS_PER_SHEET) {
                    throw tooManyRows(sheet.getSheetName());
                }
                List<List<String>> rows = new ArrayList<>();
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    List<String> values = new ArrayList<>();
                    if (row != null && row.getLastCellNum() > 0) {
                        for (int c = 0; c < Math.min(row.getLastCellNum(), MAX_COLUMNS); c++) {
                            values.add(cellText(row.getCell(c), formatter));
                        }
                    }
                    rows.add(values);
                }
                sheets.add(toSheet(sheet.getSheetName(), rows));
            }
            return new SpreadsheetWorkbook(fileName, format, sheets);
        }
    }

    private String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                ? isoDate(cell.getLocalDateTimeCellValue())
                : plainNumber(cell.getNumericCellValue());
            case ERROR -> formatter.formatCellValue(cell);
            default -> "";
        };
    }

    private static String isoDate(LocalDateTime value) {
        if (value == null) {
            return "";
        }
        return value.toLocalTime().equals(LocalTime.MIDNIGHT) ? value.format(DATE) : value.format(DATE_TIME);
    }

    static String plainNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Double.toString(value);
        }
        String text = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return "-0".equals(text) ? "0" : text;
    }

    private SpreadsheetWorkbook parseCsv(InputStream in, String fileName) throws IOException {
        BOMInputStream bomFree = BOMInputStream.builder().setInputStream(in).get();
        BufferedReader reader = new BufferedReader(new InputStreamReader(bomFree, StandardCharsets.UTF_8));
        reader.mark(MAX_FILE_BYTES + 1);
        char delimiter = detectDelimiter(reader.readLine());
        reader.reset();

        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
            .setDelimiter(delimiter)
            .setIgnoreSurroundingSpaces(true)
            .build();
        List<List<String>> rows = new ArrayList<>();
        try (CSVParser parser = csvFormat.parse(reader)) {
            for (CSVRecord record : parser) {
                if (rows.size() >= MAX_ROWS_PER_SHEET) {
                    throw tooManyRows(fileName);
                }
                List<String> values = new ArrayList<>(record.size());
                for (int c = 0; c < Math.min(record.size(), MAX_COLUMNS); c++) {
                    values.add(record.get(c).trim());
                }
                rows.add(values);
            }
        } catch (IllegalStateException | java.io.UncheckedIOException e) {
            throw new SpreadsheetParseException("'" + fileName + "' is not a valid CSV file: " + e.getMessage(), e);
        }
        return new SpreadsheetWorkbook(fileName, "csv", List.of(toSheet(baseName(fileName), rows)));
    }

    static char detectDelimiter(String firstLine) {
        if (firstLine == null) {
            return ',';
        }
        char best = ',';
        long bestCount = firstLine.chars().filter(ch -> ch == ',').count();
        for (char candidate : new char[] {';', '\t'}) {
            long count = firstLine.chars().filter(ch -> ch == candidate).count();
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    private static String baseName(String fileName) {
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static SpreadsheetWorkbook.Sheet toSheet(String name, List<List<String>> rows) {
        int lastRow = rows.size();
        while (lastRow > 0 && isBlank(rows.get(lastRow - 1))) {
            lastRow--;
        }
        List<List<String>> kept = rows.subList(0, lastRow);
        int columns = 0;
        for (List<String> row : kept) {
            for (int c = row.size(); c > columns; c--) {
                if (!row.get(c - 1).isEmpty()) {
                    columns = c;
                    break;
                }
            }
        }
        List<List<String>> padded = new ArrayList<>(kept.size());
        for (List<String> row : kept) {
            List<String> copy = new ArrayList<>(row.subList(0, Math.min(row.size(), columns)));
            while (copy.size() < columns) {
                copy.add("");
            }
            padded.add(List.copyOf(copy));
        }
        return new SpreadsheetWorkbook.Sheet(name, padded.size(), columns, List.copyOf(padded));
    }

    private static boolean isBlank(List<String> row) {
        return row.stream().allMatch(String::isEmpty);
    }

    private static SpreadsheetParseException tooManyRows(String sheet) {
        return new SpreadsheetParseException(
            "Sheet '" + sheet + "' has more than " + MAX_ROWS_PER_SHEET + " rows; split the file and import it in parts.");
    }

    public static String columnLetter(int column) {
        StringBuilder sb = new StringBuilder();
        for (int n = column; n > 0; n = (n - 1) / 26) {
            sb.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return sb.toString();
    }

    public static int columnIndex(String letters) {
        if (letters == null || letters.isEmpty()) {
            return -1;
        }
        int n = 0;
        for (char ch : letters.toUpperCase(Locale.ROOT).toCharArray()) {
            if (ch < 'A' || ch > 'Z') {
                return -1;
            }
            n = n * 26 + (ch - 'A' + 1);
        }
        return n;
    }
}
