package com.ecabin.ledger.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Minimal standards-compliant XLSX writer using inline strings and bounded-memory streaming. */
public final class XlsxStreamWriter {
    private static final long ROWS_PER_SHEET = 1_048_576L;
    private final ZipOutputStream zip;
    private final List<String> headers;
    private int sheetNumber;
    private long rowNumber;
    private boolean finished;

    public XlsxStreamWriter(OutputStream output, List<String> headers, long totalRows) throws IOException {
        this.zip = new ZipOutputStream(output, StandardCharsets.UTF_8);
        this.headers = headers;
        int sheetCount = Math.max(1, (int) Math.ceil((double) totalRows / (ROWS_PER_SHEET - 1)));
        writeEntry("[Content_Types].xml", contentTypes(sheetCount));
        writeEntry("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        writeEntry("xl/workbook.xml", workbook(sheetCount));
        writeEntry("xl/_rels/workbook.xml.rels", workbookRelationships(sheetCount));
        beginSheet();
        writeRow(headers);
    }

    public void writeRow(List<String> values) throws IOException {
        if (finished) throw new IllegalStateException("The workbook has already been finalized.");
        if (rowNumber >= ROWS_PER_SHEET) {
            closeSheet();
            beginSheet();
            writeRow(headers);
        }
        rowNumber++;
        write("<row r=\"" + rowNumber + "\">");
        for (int column=0; column<values.size(); column++) {
            String reference = columnName(column + 1) + rowNumber;
            write("<c r=\"" + reference + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + escape(values.get(column)) + "</t></is></c>");
        }
        write("</row>");
    }

    public void finish() throws IOException {
        if (finished) return;
        closeSheet();
        zip.finish();
        zip.flush();
        finished = true;
    }

    private void beginSheet() throws IOException {
        sheetNumber++;
        rowNumber = 0;
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + sheetNumber + ".xml"));
        write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
    }

    private void closeSheet() throws IOException {
        write("</sheetData></worksheet>");
        zip.closeEntry();
    }

    private void writeEntry(String name, String contents) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        write(contents);
        zip.closeEntry();
    }

    private void write(String value) throws IOException { zip.write(value.getBytes(StandardCharsets.UTF_8)); }

    private String contentTypes(int count) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
        for (int sheet=1; sheet<=count; sheet++) xml.append("<Override PartName=\"/xl/worksheets/sheet").append(sheet).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        return xml.append("</Types>").toString();
    }

    private String workbook(int count) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        for (int sheet=1; sheet<=count; sheet++) xml.append("<sheet name=\"").append(sheet==1 ? "Records" : "Records " + sheet).append("\" sheetId=\"").append(sheet).append("\" r:id=\"rId").append(sheet).append("\"/>");
        return xml.append("</sheets></workbook>").toString();
    }

    private String workbookRelationships(int count) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int sheet=1; sheet<=count; sheet++) xml.append("<Relationship Id=\"rId").append(sheet).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(sheet).append(".xml\"/>");
        return xml.append("</Relationships>").toString();
    }

    private String columnName(int index) {
        StringBuilder name = new StringBuilder();
        while (index > 0) { index--; name.insert(0, (char) ('A' + index % 26)); index /= 26; }
        return name.toString();
    }

    private String escape(String input) {
        StringBuilder escaped = new StringBuilder();
        input.codePoints().filter(code -> code == 0x9 || code == 0xA || code == 0xD || code >= 0x20)
            .forEach(code -> { switch (code) { case '&' -> escaped.append("&amp;"); case '<' -> escaped.append("&lt;"); case '>' -> escaped.append("&gt;"); case '"' -> escaped.append("&quot;"); case '\'' -> escaped.append("&apos;"); default -> escaped.appendCodePoint(code); } });
        return escaped.toString();
    }
}
