package com.tallybackend.web;

import com.tallybackend.cache.TallyCacheReadService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class CacheExportService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DISPLAY_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy hh:mm a").withZone(ZoneId.systemDefault());
    private static final Color PDF_PAGE_BORDER = new Color(213, 223, 235);
    private static final Color PDF_HEADER_BG = new Color(17, 58, 88);
    private static final Color PDF_HEADER_TEXT = Color.WHITE;
    private static final Color PDF_META_BG = new Color(240, 246, 252);
    private static final Color PDF_TABLE_HEADER_BG = new Color(231, 239, 247);
    private static final Color PDF_TABLE_BORDER = new Color(198, 210, 224);
    private static final Color PDF_TABLE_ALT_ROW = new Color(248, 251, 255);
    private static final Color PDF_TEXT = new Color(35, 48, 64);
    private static final Color PDF_MUTED_TEXT = new Color(96, 112, 131);

    private final TallyCacheReadService tallyCacheReadService;

    public CacheExportService(TallyCacheReadService tallyCacheReadService) {
        this.tallyCacheReadService = tallyCacheReadService;
    }

    public ExportFile export(String category, String dataset, String format, String company, Map<String, String> params) {
        Map<String, Object> response = fetchDataset(category, dataset, company, params);
        List<Map<String, Object>> rows = extractRows(response);
        List<String> columns = resolveColumns(dataset, rows);
        String title = buildTitle(category, dataset);
        String fileName = buildFileName(dataset, format);
        CompanyPdfProfile companyProfile = buildCompanyPdfProfile(company);

        try {
            if ("xlsx".equalsIgnoreCase(format) || "excel".equalsIgnoreCase(format)) {
                return new ExportFile(
                        fileName,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        buildWorkbook(title, company, params, columns, rows, dataset)
                );
            }
            if ("pdf".equalsIgnoreCase(format)) {
                return new ExportFile(
                        fileName,
                        "application/pdf",
                        buildPdf(title, company, params, columns, rows, dataset, companyProfile)
                );
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to generate export file.", exception);
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported export format: " + format);
    }

    private Map<String, Object> fetchDataset(String category, String dataset, String company, Map<String, String> params) {
        if ("masters".equalsIgnoreCase(category)) {
            if ("companies".equalsIgnoreCase(dataset)) {
                return tallyCacheReadService.companies(company);
            }
            if ("groups".equalsIgnoreCase(dataset)) {
                return tallyCacheReadService.groups(company);
            }
            if ("ledgers".equalsIgnoreCase(dataset)) {
                return tallyCacheReadService.ledgers(company);
            }
            if ("uoms".equalsIgnoreCase(dataset)) {
                return tallyCacheReadService.uoms(company);
            }
            if ("currencies".equalsIgnoreCase(dataset)) {
                return tallyCacheReadService.currencies(company);
            }
            if ("stock-groups".equalsIgnoreCase(dataset)) {
                return tallyCacheReadService.stockGroups(company);
            }
            if ("stock-items".equalsIgnoreCase(dataset)) {
                return tallyCacheReadService.stockItems(company);
            }
        }

        if ("reports".equalsIgnoreCase(category)) {
            if ("day-book".equalsIgnoreCase(dataset)) {
                return tallyCacheReadService.dayBook(required(params, "from_date"), required(params, "to_date"), company);
            }
            if ("ledger-vouchers".equalsIgnoreCase(dataset)) {
                return tallyCacheReadService.ledgerVouchers(
                        required(params, "from_date"),
                        required(params, "to_date"),
                        params.getOrDefault("ledger_name", ""),
                        company
                );
            }
            if ("balance-sheet".equalsIgnoreCase(dataset)) {
                return tallyCacheReadService.balanceSheet(required(params, "from_date"), required(params, "to_date"), company);
            }
            if ("profit-loss".equalsIgnoreCase(dataset)) {
                return tallyCacheReadService.profitLoss(required(params, "from_date"), required(params, "to_date"), company);
            }
        }

        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Export dataset not supported: " + category + "/" + dataset);
    }

    private String required(Map<String, String> params, String key) {
        String value = params.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required parameter: " + key);
        }
        return value.trim();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractRows(Map<String, Object> response) {
        Object data = response.get("data");
        if (data instanceof List) {
            List<?> items = (List<?>) data;
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (Object item : items) {
                if (item instanceof Map) {
                    rows.add((Map<String, Object>) item);
                }
            }
            return rows;
        }
        return new ArrayList<Map<String, Object>>();
    }

    private List<String> resolveColumns(String dataset, List<Map<String, Object>> rows) {
        List<String> preferred = preferredColumns(dataset);
        Map<String, String> columns = new LinkedHashMap<String, String>();
        for (String column : preferred) {
            if (isExportableColumn(column)) {
                columns.put(canonicalColumnKey(dataset, normalizeColumnKey(column)), column);
            }
        }
        for (Map<String, Object> row : rows) {
            for (String key : row.keySet()) {
                if (!isExportableColumn(key)) {
                    continue;
                }
                String normalized = canonicalColumnKey(dataset, normalizeColumnKey(key));
                if (!columns.containsKey(normalized)) {
                    columns.put(normalized, key);
                }
            }
        }
        return new ArrayList<String>(columns.values());
    }

    private List<String> preferredColumns(String dataset) {
        if ("companies".equalsIgnoreCase(dataset)) {
            return Arrays.asList("name", "reservedName");
        }
        if ("groups".equalsIgnoreCase(dataset) || "ledgers".equalsIgnoreCase(dataset) || "stock-groups".equalsIgnoreCase(dataset)) {
            return Arrays.asList("name", "parent", "reservedName");
        }
        if ("uoms".equalsIgnoreCase(dataset)) {
            return Arrays.asList("name", "originalName", "reservedName", "decimalPlaces", "isSimpleUnit");
        }
        if ("currencies".equalsIgnoreCase(dataset)) {
            return Arrays.asList("name", "originalName", "symbol", "decimalSymbol", "decimalPlaces", "warningNote");
        }
        if ("stock-items".equalsIgnoreCase(dataset)) {
            return Arrays.asList("name", "parent", "baseUnits", "hsnCode", "gstApplicable", "quantity", "rate", "value");
        }
        if ("day-book".equalsIgnoreCase(dataset)) {
            return Arrays.asList("date", "voucherType", "voucherNumber", "partyLedger", "amount", "narration");
        }
        if ("ledger-vouchers".equalsIgnoreCase(dataset)) {
            return Arrays.asList("date", "ledger", "voucherType", "debitAmount", "creditAmount");
        }
        if ("balance-sheet".equalsIgnoreCase(dataset) || "profit-loss".equalsIgnoreCase(dataset)) {
            return Arrays.asList("name", "amount", "kind");
        }
        return new ArrayList<String>();
    }

    private byte[] buildWorkbook(String title,
                                 String company,
                                 Map<String, String> params,
                                 List<String> columns,
                                 List<Map<String, Object>> rows,
                                 String dataset) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        try {
            Sheet sheet = workbook.createSheet(title.length() > 28 ? title.substring(0, 28) : title);

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setFillForegroundColor((short) 22);
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            int rowIndex = 0;
            rowIndex = writeMetaRow(sheet, rowIndex, "Report", title);
            rowIndex = writeMetaRow(sheet, rowIndex, "Company", company == null || company.trim().isEmpty() ? "All Companies" : company);
            for (Map.Entry<String, String> entry : params.entrySet()) {
              rowIndex = writeMetaRow(sheet, rowIndex, humanize(entry.getKey()), entry.getValue());
            }
            rowIndex++;

            Row headerRow = sheet.createRow(rowIndex++);
            for (int index = 0; index < columns.size(); index++) {
                Cell cell = headerRow.createCell(index);
                cell.setCellValue(humanize(columns.get(index)));
                cell.setCellStyle(headerStyle);
            }

            for (Map<String, Object> dataRow : rows) {
                Row row = sheet.createRow(rowIndex++);
                for (int index = 0; index < columns.size(); index++) {
                    row.createCell(index).setCellValue(exportCellText(dataset, columns.get(index), dataRow));
                }
            }

            for (int index = 0; index < columns.size(); index++) {
                sheet.autoSizeColumn(index);
                int currentWidth = sheet.getColumnWidth(index);
                sheet.setColumnWidth(index, Math.min(currentWidth + 512, 12000));
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        } finally {
            workbook.close();
        }
    }

    private int writeMetaRow(Sheet sheet, int rowIndex, String label, String value) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(label);
        row.createCell(1).setCellValue(value == null ? "" : value);
        return rowIndex + 1;
    }

    private byte[] buildPdf(String title,
                            String company,
                            Map<String, String> params,
                            List<String> columns,
                            List<Map<String, Object>> rows,
                            String dataset,
                            CompanyPdfProfile companyProfile) throws IOException {
        PDDocument document = new PDDocument();
        try {
            boolean landscape = columns.size() > 5;
            PDRectangle pageSize = landscape
                    ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth())
                    : PDRectangle.A4;
            PdfTableLayout layout = new PdfTableLayout(pageSize, 36f, 34f, 40f, 40f);
            List<Float> columnWidths = resolvePdfColumnWidths(dataset, columns, rows, layout.tableWidth);
            float headerHeight = 26f;
            float rowPadding = 6f;
            float lineHeight = 11f;

            PdfPageContext context = createPdfPage(document, layout, title, company, params, companyProfile, 1);
            drawPdfTableHeader(context.stream, columns, columnWidths, layout.left, context.y, headerHeight);
            context.y -= headerHeight;

            if (rows.isEmpty()) {
                float emptyHeight = 30f;
                if (!hasSpaceForRow(context.y, layout.bottom, emptyHeight)) {
                    context = nextPdfPage(document, context, layout, title, company, params, companyProfile, columns, columnWidths, headerHeight);
                }
                drawEmptyPdfState(context.stream, layout.left, context.y, layout.tableWidth, emptyHeight);
                context.y -= emptyHeight;
            }

            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Map<String, Object> row = rows.get(rowIndex);
                RowRenderPlan plan = buildRowRenderPlan(dataset, columns, columnWidths, row, rowPadding, lineHeight);
                if (!hasSpaceForRow(context.y, layout.bottom, plan.height)) {
                    context = nextPdfPage(document, context, layout, title, company, params, companyProfile, columns, columnWidths, headerHeight);
                }
                drawPdfRow(context.stream, layout.left, context.y, columnWidths, plan, rowIndex % 2 == 1);
                context.y -= plan.height;
            }

            drawPdfFooter(context, title);
            context.stream.close();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            document.save(outputStream);
            return outputStream.toByteArray();
        } finally {
            document.close();
        }
    }

    private PdfPageContext nextPdfPage(PDDocument document,
                                       PdfPageContext current,
                                       PdfTableLayout layout,
                                       String title,
                                       String company,
                                       Map<String, String> params,
                                       CompanyPdfProfile companyProfile,
                                       List<String> columns,
                                       List<Float> columnWidths,
                                       float headerHeight) throws IOException {
        drawPdfFooter(current, title);
        current.stream.close();
        PdfPageContext next = createPdfPage(document, layout, title, company, params, companyProfile, current.pageNumber + 1);
        drawPdfTableHeader(next.stream, columns, columnWidths, layout.left, next.y, headerHeight);
        next.y -= headerHeight;
        return next;
    }

    private PdfPageContext createPdfPage(PDDocument document,
                                         PdfTableLayout layout,
                                         String title,
                                         String company,
                                         Map<String, String> params,
                                         CompanyPdfProfile companyProfile,
                                         int pageNumber) throws IOException {
        PDPage page = new PDPage(layout.pageSize);
        document.addPage(page);
        PDPageContentStream stream = new PDPageContentStream(document, page);

        drawFilledRect(stream, 0f, 0f, layout.pageWidth, layout.pageHeight, Color.WHITE);
        drawRectOutline(stream, layout.left - 10f, layout.bottom - 12f,
                layout.pageWidth - layout.left - layout.right + 20f,
                layout.pageHeight - layout.top - layout.bottom + 24f,
                PDF_PAGE_BORDER);
        drawFilledRect(stream, layout.left - 10f, layout.pageHeight - layout.top - 42f,
                layout.pageWidth - layout.left - layout.right + 20f, 42f, PDF_HEADER_BG);

        float headerY = layout.pageHeight - layout.top - 16f;
        drawText(stream, PDType1Font.HELVETICA_BOLD, 17f, layout.left + 4f, headerY, title, PDF_HEADER_TEXT);
        drawText(stream, PDType1Font.HELVETICA, 9f, layout.pageWidth - layout.right - 140f, headerY,
                "Generated: " + DISPLAY_TIMESTAMP_FORMAT.format(Instant.now()), PDF_HEADER_TEXT);

        float metaTop = layout.pageHeight - layout.top - 58f;
        float companyBoxWidth = Math.min(292f, layout.tableWidth * 0.46f);
        float filtersBoxWidth = layout.tableWidth - companyBoxWidth - 12f;
        List<String> companyNameLines = wrapText(companyProfile.displayName, PDType1Font.HELVETICA_BOLD, 12f, companyBoxWidth - 20f);
        List<String> companyDetailLines = wrapText(String.join("\n", companyProfile.detailLines), PDType1Font.HELVETICA, 9f, companyBoxWidth - 20f);
        List<String> filtersLines = wrapText(filtersSummary(params), PDType1Font.HELVETICA, 10f, filtersBoxWidth - 20f);
        float companyTextHeight = (companyNameLines.size() * 13f)
                + (companyDetailLines.isEmpty() ? 0f : 4f + (companyDetailLines.size() * 11f));
        float filtersTextHeight = filtersLines.isEmpty() ? 12f : filtersLines.size() * 12f;
        float metaHeight = Math.max(44f, 28f + Math.max(companyTextHeight, filtersTextHeight) + 10f);
        drawFilledRect(stream, layout.left, metaTop - metaHeight, companyBoxWidth, metaHeight, PDF_META_BG);
        drawFilledRect(stream, layout.left + companyBoxWidth + 12f, metaTop - metaHeight, filtersBoxWidth, metaHeight, PDF_META_BG);
        drawRectOutline(stream, layout.left, metaTop - metaHeight, companyBoxWidth, metaHeight, PDF_TABLE_BORDER);
        drawRectOutline(stream, layout.left + companyBoxWidth + 12f, metaTop - metaHeight, filtersBoxWidth, metaHeight, PDF_TABLE_BORDER);

        drawText(stream, PDType1Font.HELVETICA_BOLD, 9f, layout.left + 10f, metaTop - 14f, "COMPANY", PDF_MUTED_TEXT);
        float companyTextY = metaTop - 29f;
        for (String line : companyNameLines) {
            drawText(stream, PDType1Font.HELVETICA_BOLD, 12f, layout.left + 10f, companyTextY, line, PDF_TEXT);
            companyTextY -= 13f;
        }
        if (!companyDetailLines.isEmpty()) {
            companyTextY -= 4f;
            for (String line : companyDetailLines) {
                drawText(stream, PDType1Font.HELVETICA, 9f, layout.left + 10f, companyTextY, line, PDF_TEXT);
                companyTextY -= 11f;
            }
        }
        drawText(stream, PDType1Font.HELVETICA_BOLD, 9f, layout.left + companyBoxWidth + 22f, metaTop - 14f,
                "FILTERS", PDF_MUTED_TEXT);
        drawWrappedText(stream, PDType1Font.HELVETICA, 10f, layout.left + companyBoxWidth + 22f, metaTop - 29f,
                filtersSummary(params), filtersBoxWidth - 20f, 12f, PDF_TEXT);

        float summaryTop = metaTop - metaHeight - 14f;
        drawText(stream, PDType1Font.HELVETICA, 9f, layout.left, summaryTop,
                "Rows: " + rowsCountLabel(params), PDF_MUTED_TEXT);
        drawText(stream, PDType1Font.HELVETICA, 9f, layout.pageWidth - layout.right - 70f, summaryTop,
                "Page " + pageNumber, PDF_MUTED_TEXT);

        return new PdfPageContext(page, stream, pageNumber, summaryTop - 12f);
    }

    private void drawPdfTableHeader(PDPageContentStream stream,
                                    List<String> columns,
                                    List<Float> widths,
                                    float startX,
                                    float topY,
                                    float height) throws IOException {
        drawFilledRect(stream, startX, topY - height, sum(widths), height, PDF_TABLE_HEADER_BG);
        drawRectOutline(stream, startX, topY - height, sum(widths), height, PDF_TABLE_BORDER);
        float x = startX;
        for (int index = 0; index < columns.size(); index++) {
            float width = widths.get(index);
            if (index > 0) {
                drawLine(stream, x, topY, x, topY - height, PDF_TABLE_BORDER);
            }
            List<String> lines = wrapText(humanize(columns.get(index)), PDType1Font.HELVETICA_BOLD, 9f, width - 12f);
            float textY = topY - 11f;
            for (String line : lines) {
                drawText(stream, PDType1Font.HELVETICA_BOLD, 9f, x + 6f, textY, line, PDF_TEXT);
                textY -= 10f;
            }
            x += width;
        }
    }

    private RowRenderPlan buildRowRenderPlan(String dataset,
                                             List<String> columns,
                                             List<Float> widths,
                                             Map<String, Object> row,
                                             float padding,
                                             float lineHeight) throws IOException {
        List<List<String>> cells = new ArrayList<List<String>>();
        int maxLines = 1;
        for (int index = 0; index < columns.size(); index++) {
            String value = normalizePdfCellValue(exportCellText(dataset, columns.get(index), row));
            List<String> wrapped = wrapText(value, PDType1Font.HELVETICA, 8.5f, widths.get(index) - (padding * 2f));
            if (wrapped.isEmpty()) {
                wrapped.add("-");
            }
            cells.add(wrapped);
            maxLines = Math.max(maxLines, wrapped.size());
        }
        float height = (maxLines * lineHeight) + (padding * 2f);
        return new RowRenderPlan(cells, height, padding, lineHeight);
    }

    private void drawPdfRow(PDPageContentStream stream,
                            float startX,
                            float topY,
                            List<Float> widths,
                            RowRenderPlan plan,
                            boolean alternate) throws IOException {
        float tableWidth = sum(widths);
        drawFilledRect(stream, startX, topY - plan.height, tableWidth, plan.height, alternate ? PDF_TABLE_ALT_ROW : Color.WHITE);
        drawRectOutline(stream, startX, topY - plan.height, tableWidth, plan.height, PDF_TABLE_BORDER);
        float x = startX;
        for (int index = 0; index < widths.size(); index++) {
            float width = widths.get(index);
            if (index > 0) {
                drawLine(stream, x, topY, x, topY - plan.height, PDF_TABLE_BORDER);
            }
            float textY = topY - plan.padding - 8.5f;
            for (String line : plan.cells.get(index)) {
                drawText(stream, PDType1Font.HELVETICA, 8.5f, x + plan.padding, textY, line, PDF_TEXT);
                textY -= plan.lineHeight;
            }
            x += width;
        }
    }

    private void drawEmptyPdfState(PDPageContentStream stream,
                                   float x,
                                   float topY,
                                   float width,
                                   float height) throws IOException {
        drawFilledRect(stream, x, topY - height, width, height, Color.WHITE);
        drawRectOutline(stream, x, topY - height, width, height, PDF_TABLE_BORDER);
        drawText(stream, PDType1Font.HELVETICA_OBLIQUE, 10f, x + 10f, topY - 18f,
                "No records available for the selected filters.", PDF_MUTED_TEXT);
    }

    private void drawPdfFooter(PdfPageContext context, String title) throws IOException {
        float footerY = 22f;
        drawLine(context.stream, context.page.getMediaBox().getLowerLeftX() + 30f, footerY + 10f,
                context.page.getMediaBox().getUpperRightX() - 30f, footerY + 10f, PDF_TABLE_BORDER);
        drawText(context.stream, PDType1Font.HELVETICA, 8f, 36f, footerY, title + " - A4 export", PDF_MUTED_TEXT);
        drawText(context.stream, PDType1Font.HELVETICA, 8f,
                context.page.getMediaBox().getUpperRightX() - 72f, footerY, "Page " + context.pageNumber, PDF_MUTED_TEXT);
    }

    private boolean hasSpaceForRow(float y, float bottom, float rowHeight) {
        return (y - rowHeight) >= bottom;
    }

    private List<Float> resolvePdfColumnWidths(String dataset,
                                               List<String> columns,
                                               List<Map<String, Object>> rows,
                                               float tableWidth) throws IOException {
        List<Float> weights = new ArrayList<Float>();
        for (String column : columns) {
            float weight = Math.max(1f, approximateColumnWeight(humanize(column)));
            weights.add(weight);
        }

        int sampleCount = Math.min(rows.size(), 20);
        for (int rowIndex = 0; rowIndex < sampleCount; rowIndex++) {
            Map<String, Object> row = rows.get(rowIndex);
            for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                String value = normalizePdfCellValue(exportCellText(dataset, columns.get(columnIndex), row));
                weights.set(columnIndex, Math.max(weights.get(columnIndex), approximateColumnWeight(value)));
            }
        }

        float totalWeight = 0f;
        for (Float weight : weights) {
            totalWeight += weight;
        }

        float minimumWidth = columns.size() <= 4 ? 90f : columns.size() <= 6 ? 74f : 62f;
        List<Float> widths = new ArrayList<Float>();
        for (Float weight : weights) {
            widths.add(Math.max(minimumWidth, (weight / totalWeight) * tableWidth));
        }

        float totalWidth = sum(widths);
        if (totalWidth > tableWidth) {
            float shrinkRatio = tableWidth / totalWidth;
            for (int index = 0; index < widths.size(); index++) {
                widths.set(index, widths.get(index) * shrinkRatio);
            }
        } else if (totalWidth < tableWidth && !widths.isEmpty()) {
            widths.set(widths.size() - 1, widths.get(widths.size() - 1) + (tableWidth - totalWidth));
        }
        return widths;
    }

    private float approximateColumnWeight(String value) {
        String safe = value == null ? "" : value.trim();
        if (safe.isEmpty()) {
            return 1f;
        }
        int length = Math.min(safe.length(), 28);
        if (safe.matches(".*\\d.*")) {
            return Math.max(2f, length * 0.72f);
        }
        return Math.max(2.4f, length * 0.9f);
    }

    private List<String> wrapText(String text,
                                  PDFont font,
                                  float fontSize,
                                  float maxWidth) throws IOException {
        List<String> lines = new ArrayList<String>();
        String normalized = normalizePdfCellValue(text);
        if (normalized.isEmpty()) {
            return lines;
        }
        String[] paragraphs = normalized.split("\\n");
        for (String paragraph : paragraphs) {
            String current = "";
            for (String word : paragraph.split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (textWidth(candidate, font, fontSize) <= maxWidth) {
                    current = candidate;
                    continue;
                }
                if (!current.isEmpty()) {
                    lines.add(current);
                }
                if (textWidth(word, font, fontSize) <= maxWidth) {
                    current = word;
                    continue;
                }
                List<String> brokenWord = breakLongWord(word, font, fontSize, maxWidth);
                for (int index = 0; index < brokenWord.size() - 1; index++) {
                    lines.add(brokenWord.get(index));
                }
                current = brokenWord.get(brokenWord.size() - 1);
            }
            if (!current.isEmpty()) {
                lines.add(current);
            }
            if (paragraphs.length > 1 && lines.isEmpty()) {
                lines.add("");
            }
        }
        return lines;
    }

    private List<String> breakLongWord(String word,
                                       PDFont font,
                                       float fontSize,
                                       float maxWidth) throws IOException {
        List<String> chunks = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < word.length(); index++) {
            char character = word.charAt(index);
            String candidate = current.toString() + character;
            if (current.length() > 0 && textWidth(candidate, font, fontSize) > maxWidth) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
            current.append(character);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private float textWidth(String text, PDFont font, float fontSize) throws IOException {
        return font.getStringWidth(text == null ? "" : text) / 1000f * fontSize;
    }

    private void drawWrappedText(PDPageContentStream stream,
                                 PDFont font,
                                 float fontSize,
                                 float x,
                                 float y,
                                 String text,
                                 float maxWidth,
                                 float lineHeight,
                                 Color color) throws IOException {
        List<String> lines = wrapText(text, font, fontSize, maxWidth);
        float lineY = y;
        for (String line : lines) {
            drawText(stream, font, fontSize, x, lineY, line, color);
            lineY -= lineHeight;
        }
    }

    private void drawText(PDPageContentStream stream,
                          PDFont font,
                          float size,
                          float x,
                          float y,
                          String text,
                          Color color) throws IOException {
        stream.beginText();
        stream.setNonStrokingColor(color);
        stream.setFont(font, size);
        stream.newLineAtOffset(x, y);
        stream.showText(sanitizePdfText(text));
        stream.endText();
    }

    private void drawFilledRect(PDPageContentStream stream,
                                float x,
                                float y,
                                float width,
                                float height,
                                Color color) throws IOException {
        stream.setNonStrokingColor(color);
        stream.addRect(x, y, width, height);
        stream.fill();
    }

    private void drawRectOutline(PDPageContentStream stream,
                                 float x,
                                 float y,
                                 float width,
                                 float height,
                                 Color color) throws IOException {
        stream.setStrokingColor(color);
        stream.addRect(x, y, width, height);
        stream.stroke();
    }

    private void drawLine(PDPageContentStream stream,
                          float startX,
                          float startY,
                          float endX,
                          float endY,
                          Color color) throws IOException {
        stream.setStrokingColor(color);
        stream.moveTo(startX, startY);
        stream.lineTo(endX, endY);
        stream.stroke();
    }

    private float sum(List<Float> values) {
        float total = 0f;
        for (Float value : values) {
            total += value;
        }
        return total;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<String> values = new ArrayList<String>();
            for (Object item : list) {
                values.add(stringValue(item));
            }
            return String.join(", ", values);
        }
        if (value instanceof Map) {
            return value.toString();
        }
        return String.valueOf(value);
    }

    private String normalizePdfCellValue(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace('\u2026', '.')
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replaceAll("[\\r\\t]+", " ")
                .replaceAll("\\s*\\n\\s*", "\n")
                .replaceAll(" {2,}", " ")
                .trim();
    }

    private boolean isExportableColumn(String key) {
        String normalized = normalizeColumnKey(key);
        if (normalized.isEmpty()) {
            return false;
        }
        return !"payloadjson".equals(normalized)
                && !"rowindex".equals(normalized)
                && !"snapshotid".equals(normalized)
                && !"contenthash".equals(normalized);
    }

    private String normalizeColumnKey(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ENGLISH);
    }

    private String canonicalColumnKey(String dataset, String normalizedKey) {
        if ("stock-items".equalsIgnoreCase(dataset)) {
            if ("rateper".equals(normalizedKey) || "openingrate".equals(normalizedKey) || "rateperunit".equals(normalizedKey)) {
                return "rate";
            }
            if ("openingvalue".equals(normalizedKey) || "closingvalue".equals(normalizedKey)) {
                return "value";
            }
            if ("openingquantity".equals(normalizedKey) || "closingbalance".equals(normalizedKey)) {
                return "quantity";
            }
        }
        return normalizedKey;
    }

    private Object resolveCellValue(String dataset, String column, Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Object direct = row.get(column);
        if (direct != null) {
            return direct;
        }
        String normalizedColumn = canonicalColumnKey(dataset, normalizeColumnKey(column));
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String normalizedKey = canonicalColumnKey(dataset, normalizeColumnKey(entry.getKey()));
            if (normalizedColumn.equals(normalizedKey) && entry.getValue() != null) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String exportCellText(String dataset, String column, Map<String, Object> row) {
        Object value = resolveCellValue(dataset, column, row);
        String normalizedColumn = canonicalColumnKey(dataset, normalizeColumnKey(column));
        if (isAbsoluteAmountColumn(normalizedColumn)) {
            return formatAbsoluteNumeric(value);
        }
        return stringValue(value);
    }

    private boolean isAbsoluteAmountColumn(String normalizedColumn) {
        return "amount".equals(normalizedColumn)
                || "debitamount".equals(normalizedColumn)
                || "creditamount".equals(normalizedColumn);
    }

    private String formatAbsoluteNumeric(Object value) {
        if (value == null) {
            return "";
        }
        String text = stringValue(value).trim();
        if (text.isEmpty()) {
            return "";
        }
        try {
            return new BigDecimal(text).abs().setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString();
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    private String sanitizePdfText(String value) {
        return normalizePdfCellValue(value)
                .replaceAll("[^\\x20-\\x7E\\n]", "?");
    }

    private String filtersSummary(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "No additional filters";
        }
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            parts.add(humanize(entry.getKey()) + ": " + entry.getValue());
        }
        return String.join("   |   ", parts);
    }

    private String fallbackCompany(String company) {
        return company == null || company.trim().isEmpty() ? "All Companies" : company.trim();
    }

    private CompanyPdfProfile buildCompanyPdfProfile(String company) {
        String fallbackName = fallbackCompany(company);
        Map<String, Object> companyCurrency = firstResponseRow(tallyCacheReadService.companyCurrency(company));
        Map<String, Object> companyFeatures = firstResponseRow(tallyCacheReadService.companyFeatures(company));
        Map<String, Object> gstRegistration = firstResponseRow(tallyCacheReadService.gstRegistration(company));

        String displayName = firstNonBlank(
                readAny(gstRegistration, "COMPANYNAME", "LEGALNAME", "LEGALENTITYNAME", "NAME", "BUSINESSNAME"),
                readAny(companyCurrency, "MAILINGNAME"),
                readAny(companyFeatures, "NAME"),
                readAny(companyCurrency, "NAME"),
                fallbackName
        );

        List<String> detailLines = new ArrayList<String>();
        appendLine(detailLines, firstNonBlank(
                readAny(gstRegistration, "ADDRESS"),
                joinAddress(gstRegistration)
        ));

        String cityStateLine = joinNonBlank(", ",
                firstNonBlank(readAny(gstRegistration, "CITY"), readAny(gstRegistration, "PLACE"), readAny(gstRegistration, "DISTRICT")),
                firstNonBlank(readAny(gstRegistration, "STATENAME"), readAny(companyFeatures, "STATENAME")),
                firstNonBlank(readAny(gstRegistration, "PINCODE"), readAny(companyFeatures, "PINCODE"))
        );
        appendLine(detailLines, cityStateLine);

        appendLine(detailLines, prefixedValue("MSME No", firstNonBlank(
                readAny(gstRegistration, "MSMENO"),
                readAny(gstRegistration, "MSMEREGISTRATIONNUMBER"),
                readAny(gstRegistration, "UDYAMREGISTRATIONNUMBER")
        )));
        appendLine(detailLines, prefixedValue("MSME Type", firstNonBlank(
                readAny(gstRegistration, "MSMETYPE"),
                readAny(gstRegistration, "ENTERPRISETYPE")
        )));
        appendLine(detailLines, prefixedValue("GSTIN/UIN", firstNonBlank(
                readAny(gstRegistration, "GSTIN"),
                readAny(gstRegistration, "GSTINUIN"),
                readAny(gstRegistration, "PARTYGSTIN"),
                readAny(gstRegistration, "REGISTRATIONNUMBER")
        )));

        String stateCodeLine = joinNonBlank(", ",
                prefixedValue("State Name", firstNonBlank(
                        readAny(gstRegistration, "STATENAME"),
                        readAny(companyFeatures, "STATENAME")
                )),
                prefixedValue("Code", readAny(gstRegistration, "STATECODE"))
        );
        appendLine(detailLines, stateCodeLine);

        appendLine(detailLines, prefixedValue("Contact", joinNonBlank(", ",
                firstNonBlank(readAny(gstRegistration, "CONTACT"), readAny(gstRegistration, "CONTACTNO")),
                firstNonBlank(readAny(gstRegistration, "PHONENUMBER"), readAny(gstRegistration, "PHONE"), readAny(gstRegistration, "MOBILE"))
        )));
        appendLine(detailLines, prefixedValue("Email", firstNonBlank(
                readAny(gstRegistration, "EMAIL"),
                readAny(companyFeatures, "EMAIL")
        )));

        if (detailLines.isEmpty()) {
            detailLines.add("Company details are not available in the cached settings data.");
        }
        return new CompanyPdfProfile(displayName, detailLines);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstResponseRow(Map<String, Object> response) {
        if (response == null) {
            return new LinkedHashMap<String, Object>();
        }
        Object data = response.get("data");
        if (data instanceof List && !((List<?>) data).isEmpty()) {
            Object first = ((List<?>) data).get(0);
            if (first instanceof Map) {
                return (Map<String, Object>) first;
            }
        }
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        return new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private String readAny(Map<String, Object> row, String... keys) {
        if (row == null || row.isEmpty()) {
            return "";
        }
        for (String key : keys) {
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (normalizeColumnKey(key).equals(normalizeColumnKey(entry.getKey()))) {
                    Object value = entry.getValue();
                    if (value instanceof List) {
                        List<String> parts = new ArrayList<String>();
                        for (Object item : (List<Object>) value) {
                            String text = stringValue(item).trim();
                            if (!text.isEmpty()) {
                                parts.add(text);
                            }
                        }
                        if (!parts.isEmpty()) {
                            return String.join(", ", parts);
                        }
                    }
                    String text = stringValue(value).trim();
                    if (!text.isEmpty()) {
                        return text;
                    }
                }
            }
        }
        return "";
    }

    private String joinAddress(Map<String, Object> row) {
        List<String> parts = new ArrayList<String>();
        appendPart(parts, readAny(row, "ADDRESS1"));
        appendPart(parts, readAny(row, "ADDRESS2"));
        appendPart(parts, readAny(row, "ADDRESS3"));
        appendPart(parts, readAny(row, "ADDRESS4"));
        appendPart(parts, readAny(row, "STREET"));
        appendPart(parts, readAny(row, "AREA"));
        return String.join(", ", parts);
    }

    private void appendLine(List<String> lines, String value) {
        if (value != null && !value.trim().isEmpty()) {
            lines.add(value.trim());
        }
    }

    private void appendPart(List<String> parts, String value) {
        if (value != null && !value.trim().isEmpty()) {
            parts.add(value.trim());
        }
    }

    private String joinNonBlank(String delimiter, String... values) {
        List<String> parts = new ArrayList<String>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                parts.add(value.trim());
            }
        }
        return String.join(delimiter, parts);
    }

    private String prefixedValue(String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        return label + ": " + value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private String rowsCountLabel(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "Dynamic report export";
        }
        return "Filtered export";
    }

    private String humanize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        String normalized = value
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ')
                .trim();
        return normalized.substring(0, 1).toUpperCase(Locale.ENGLISH) + normalized.substring(1);
    }

    private String buildTitle(String category, String dataset) {
        return humanize(category) + " - " + humanize(dataset);
    }

    private String buildFileName(String dataset, String format) {
        return dataset + "_" + TIMESTAMP_FORMAT.format(Instant.now()) + "." + ("excel".equalsIgnoreCase(format) ? "xlsx" : format.toLowerCase(Locale.ENGLISH));
    }

    public static final class ExportFile {
        private final String fileName;
        private final String contentType;
        private final byte[] data;

        public ExportFile(String fileName, String contentType, byte[] data) {
            this.fileName = fileName;
            this.contentType = contentType;
            this.data = data;
        }

        public String getFileName() {
            return fileName;
        }

        public String getContentType() {
            return contentType;
        }

        public byte[] getData() {
            return data;
        }
    }

    private static final class CompanyPdfProfile {
        private final String displayName;
        private final List<String> detailLines;

        private CompanyPdfProfile(String displayName, List<String> detailLines) {
            this.displayName = displayName == null || displayName.trim().isEmpty() ? "All Companies" : displayName.trim();
            this.detailLines = detailLines == null ? new ArrayList<String>() : detailLines;
        }
    }

    private static final class PdfTableLayout {
        private final PDRectangle pageSize;
        private final float top;
        private final float right;
        private final float bottom;
        private final float left;
        private final float pageWidth;
        private final float pageHeight;
        private final float tableWidth;

        private PdfTableLayout(PDRectangle pageSize, float top, float right, float bottom, float left) {
            this.pageSize = pageSize;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.left = left;
            this.pageWidth = pageSize.getWidth();
            this.pageHeight = pageSize.getHeight();
            this.tableWidth = pageWidth - left - right;
        }
    }

    private static final class PdfPageContext {
        private final PDPage page;
        private final PDPageContentStream stream;
        private final int pageNumber;
        private float y;

        private PdfPageContext(PDPage page, PDPageContentStream stream, int pageNumber, float y) {
            this.page = page;
            this.stream = stream;
            this.pageNumber = pageNumber;
            this.y = y;
        }
    }

    private static final class RowRenderPlan {
        private final List<List<String>> cells;
        private final float height;
        private final float padding;
        private final float lineHeight;

        private RowRenderPlan(List<List<String>> cells, float height, float padding, float lineHeight) {
            this.cells = cells;
            this.height = height;
            this.padding = padding;
            this.lineHeight = lineHeight;
        }
    }
}
