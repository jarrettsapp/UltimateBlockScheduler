package com.residency.export;

import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.Element;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import com.residency.db.AssignmentDAO;
import com.residency.db.BlockDAO;
import com.residency.db.ResidentDAO;
import com.residency.db.RotationDAO;
import com.residency.model.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.sql.SQLException;
import java.util.*;

public class ExportService {

    private final AssignmentDAO assignmentDAO;
    private final BlockDAO blockDAO;
    private final ResidentDAO residentDAO;
    private final RotationDAO rotationDAO;

    public ExportService() throws SQLException {
        this.assignmentDAO = new AssignmentDAO();
        this.blockDAO = new BlockDAO();
        this.residentDAO = new ResidentDAO();
        this.rotationDAO = new RotationDAO();
    }

    // ─── Excel Export ──────────────────────────────────────────────────────

    public void exportToExcel(int year, String filePath) throws Exception {
        List<Resident> residents = residentDAO.getAll();
        List<Block> blocks = blockDAO.getByYear(year);
        List<Assignment> assignments = assignmentDAO.getByYear(year);

        // Build lookup: residentId -> blockNumber -> rotationName
        Map<Integer, Map<Integer, String>> grid = new HashMap<>();
        for (Assignment a : assignments) {
            grid.computeIfAbsent(a.getResidentId(), k -> new HashMap<>())
                .put(a.getBlockNumber(), a.getRotationName());
        }

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Schedule " + year);

            // Header style
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font whiteFont = wb.createFont();
            whiteFont.setColor(IndexedColors.WHITE.getIndex());
            whiteFont.setBold(true);
            headerStyle.setFont(whiteFont);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            CellStyle subHeaderStyle = wb.createCellStyle();
            subHeaderStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            subHeaderStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            subHeaderStyle.setBorderBottom(BorderStyle.THIN);
            subHeaderStyle.setAlignment(HorizontalAlignment.CENTER);
            Font subFont = wb.createFont();
            subFont.setBold(true);
            subHeaderStyle.setFont(subFont);

            // Title row
            Row titleRow = sheet.createRow(0);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("Residency Rotation Schedule — Academic Year " + year + "-" + (year + 1));
            titleCell.setCellStyle(headerStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, blocks.size()));

            // Column headers
            Row headerRow = sheet.createRow(1);
            Cell nameHeader = headerRow.createCell(0);
            nameHeader.setCellValue("Resident (PGY)");
            nameHeader.setCellStyle(subHeaderStyle);
            sheet.setColumnWidth(0, 6000);

            for (int i = 0; i < blocks.size(); i++) {
                Cell c = headerRow.createCell(i + 1);
                c.setCellValue("Block " + blocks.get(i).getLabel());
                c.setCellStyle(subHeaderStyle);
                sheet.setColumnWidth(i + 1, 4000);
            }

            // Data rows
            CellStyle altStyle = wb.createCellStyle();
            altStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            altStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            int rowIdx = 2;
            for (Resident r : residents) {
                Row row = sheet.createRow(rowIdx++);
                Cell nameCell = row.createCell(0);
                nameCell.setCellValue(r.getName() + " (PGY-" + r.getPgyLevel() + ")");

                Map<Integer, String> resMap = grid.getOrDefault(r.getId(), Collections.emptyMap());
                for (int i = 0; i < blocks.size(); i++) {
                    String rotation = resMap.getOrDefault(blocks.get(i).getBlockNumber(), "");
                    Cell cell = row.createCell(i + 1);
                    cell.setCellValue(rotation);
                    if (rowIdx % 2 == 0) cell.setCellStyle(altStyle);
                }
            }

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                wb.write(fos);
            }
        }
    }

    // ─── PDF Export ────────────────────────────────────────────────────────

    public void exportToPdf(int year, String filePath) throws Exception {
        List<Resident> residents = residentDAO.getAll();
        List<Block> blocks = blockDAO.getByYear(year);
        List<Assignment> assignments = assignmentDAO.getByYear(year);

        Map<Integer, Map<Integer, String>> grid = new HashMap<>();
        for (Assignment a : assignments) {
            grid.computeIfAbsent(a.getResidentId(), k -> new HashMap<>())
                .put(a.getBlockNumber(), a.getRotationName());
        }

        Document doc = new Document(PageSize.A3.rotate(), 20, 20, 30, 30);
        PdfWriter.getInstance(doc, new FileOutputStream(filePath));
        doc.open();

        // Title
        com.itextpdf.text.Font titleFont = new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 16, com.itextpdf.text.Font.BOLD, BaseColor.DARK_GRAY);
        Paragraph title = new Paragraph(
            "Residency Rotation Schedule — Academic Year " + year + "-" + (year + 1), titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(15);
        doc.add(title);

        // Table: residents as rows, blocks as columns
        PdfPTable table = new PdfPTable(blocks.size() + 1);
        table.setWidthPercentage(100);
        float[] widths = new float[blocks.size() + 1];
        widths[0] = 3f;
        for (int i = 1; i <= blocks.size(); i++) widths[i] = 1.5f;
        table.setWidths(widths);

        // Header row
        com.itextpdf.text.Font headerFont = new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 8, com.itextpdf.text.Font.BOLD, BaseColor.WHITE);
        BaseColor headerBg = new BaseColor(30, 60, 114);

        addHeaderCell(table, "Resident (PGY)", headerFont, headerBg);
        for (Block b : blocks) {
            addHeaderCell(table, b.getLabel(), headerFont, headerBg);
        }

        // Data rows
        com.itextpdf.text.Font cellFont = new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 7);
        com.itextpdf.text.Font nameFontBold = new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 7, com.itextpdf.text.Font.BOLD);
        BaseColor altBg = new BaseColor(245, 245, 245);
        boolean alt = false;

        for (Resident r : residents) {
            BaseColor rowBg = alt ? altBg : BaseColor.WHITE;
            alt = !alt;

            PdfPCell nameCell = new PdfPCell(new Phrase(r.getName() + "\nPGY-" + r.getPgyLevel(), nameFontBold));
            nameCell.setBackgroundColor(rowBg);
            nameCell.setPadding(3);
            table.addCell(nameCell);

            Map<Integer, String> resMap = grid.getOrDefault(r.getId(), Collections.emptyMap());
            for (Block b : blocks) {
                String rotation = resMap.getOrDefault(b.getBlockNumber(), "");
                PdfPCell cell = new PdfPCell(new Phrase(rotation, cellFont));
                cell.setBackgroundColor(rowBg);
                cell.setPadding(3);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                table.addCell(cell);
            }
        }

        doc.add(table);
        doc.close();
    }

    private void addHeaderCell(PdfPTable table, String text, com.itextpdf.text.Font font, BaseColor bg) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(bg);
        cell.setPadding(4);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(cell);
    }
}
