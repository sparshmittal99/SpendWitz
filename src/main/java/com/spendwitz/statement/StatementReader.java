package com.spendwitz.statement;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/** Turns an uploaded statement file into either a table of strings (Excel/CSV) or page texts (PDF). */
@Component
public class StatementReader {

    static final int MAX_ROWS = 3000;
    static final int MAX_PAGES = 20;

    public sealed interface Content permits Table, Pdf {
    }

    /** Cells as strings; spreadsheet date cells are normalised to yyyy-MM-dd. */
    public record Table(List<List<String>> rows) implements Content {
    }

    /** Text per page, plus the PDF with any password removed (for scanned PDFs that need the vision model). */
    public record Pdf(List<String> pages, byte[] unlockedPdf) implements Content {

        boolean hasText() {
            return pages.stream().mapToInt(String::length).sum() > 200;
        }
    }

    public Content read(String fileName, byte[] bytes, String password) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf") || startsWith(bytes, "%PDF")) {
            return readPdf(bytes, password);
        }
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return readExcel(bytes, password);
        }
        if (name.endsWith(".csv") || name.endsWith(".txt")) {
            return readCsv(bytes);
        }
        throw new StatementException("Upload a PDF, Excel (.xlsx or .xls) or CSV statement.");
    }

    private Pdf readPdf(byte[] bytes, String password) {
        try (PDDocument document = Loader.loadPDF(bytes, password == null ? "" : password)) {
            if (document.getNumberOfPages() > MAX_PAGES) {
                throw new StatementException("This PDF has " + document.getNumberOfPages()
                        + " pages. Upload a statement of up to " + MAX_PAGES + " pages (one or two months).");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<String> pages = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document));
            }
            document.setAllSecurityToBeRemoved(true);
            ByteArrayOutputStream unlocked = new ByteArrayOutputStream();
            document.save(unlocked);
            return new Pdf(pages, unlocked.toByteArray());
        } catch (InvalidPasswordException e) {
            throw new StatementException(password == null || password.isBlank()
                    ? "This PDF is password-protected. Enter its password (banks often use part of your name and date of birth)."
                    : "That password didn't open the PDF. Check it and try again.", e);
        } catch (IOException e) {
            throw new StatementException("Couldn't read that PDF. Is it a valid statement file?", e);
        }
    }

    private Table readExcel(byte[] bytes, String password) {
        String pwd = password == null || password.isBlank() ? null : password;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes), pwd)) {
            Sheet sheet = null;
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet candidate = workbook.getSheetAt(i);
                if (sheet == null || candidate.getPhysicalNumberOfRows() > sheet.getPhysicalNumberOfRows()) {
                    sheet = candidate;
                }
            }
            if (sheet == null) {
                throw new StatementException("That Excel file has no sheets.");
            }
            DataFormatter formatter = new DataFormatter(Locale.ENGLISH);
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                if (rows.size() >= MAX_ROWS) {
                    break;
                }
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    cells.add(cellText(row.getCell(c), formatter));
                }
                rows.add(trimTrailingBlanks(cells));
            }
            return new Table(rows);
        } catch (EncryptedDocumentException e) {
            throw new StatementException(pwd == null
                    ? "This Excel file is password-protected. Enter its password."
                    : "That password didn't open the Excel file. Check it and try again.", e);
        } catch (IOException | RuntimeException e) {
            if (e instanceof StatementException se) {
                throw se;
            }
            throw new StatementException("Couldn't read that Excel file. Try saving it again as .xlsx or CSV.", e);
        }
    }

    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate().toString();
            }
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        }
        return formatter.formatCellValue(cell).trim();
    }

    private Table readCsv(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).replace("﻿", "");
        String firstLine = text.lines().findFirst().orElse("");
        char delimiter = count(firstLine, ';') > count(firstLine, ',') ? ';'
                : count(firstLine, '\t') > count(firstLine, ',') ? '\t' : ',';
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setIgnoreEmptyLines(false).get();
        List<List<String>> rows = new ArrayList<>();
        try {
            for (CSVRecord record : format.parse(new StringReader(text))) {
                if (rows.size() >= MAX_ROWS) {
                    break;
                }
                List<String> cells = new ArrayList<>();
                record.forEach(v -> cells.add(v.trim()));
                rows.add(trimTrailingBlanks(cells));
            }
        } catch (IOException | IllegalStateException | java.io.UncheckedIOException e) {
            throw new StatementException("Couldn't read that CSV file.", e);
        }
        return new Table(rows);
    }

    private static List<String> trimTrailingBlanks(List<String> cells) {
        int end = cells.size();
        while (end > 0 && cells.get(end - 1).isBlank()) {
            end--;
        }
        return new ArrayList<>(cells.subList(0, end));
    }

    private static long count(String s, char c) {
        return s.chars().filter(ch -> ch == c).count();
    }

    private static boolean startsWith(byte[] bytes, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < p.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (bytes[i] != p[i]) {
                return false;
            }
        }
        return true;
    }
}
