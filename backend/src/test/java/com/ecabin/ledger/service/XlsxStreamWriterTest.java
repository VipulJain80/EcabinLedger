package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class XlsxStreamWriterTest {
    @Test
    void writesAValidWorkbookWithEscapedTextAndHeaders() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        XlsxStreamWriter writer = new XlsxStreamWriter(bytes, List.of("Defect ID", "Notes"), 1);
        writer.writeRow(List.of("D-1", "Latch <repaired> & checked"));
        writer.finish();

        byte[] output = bytes.toByteArray();
        assertEquals(0x50, Byte.toUnsignedInt(output[0]));
        assertEquals(0x4b, Byte.toUnsignedInt(output[1]));
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output))) {
            java.util.zip.ZipEntry entry;
            String worksheet = null;
            boolean hasWorkbook = false;
            while ((entry = zip.getNextEntry()) != null) {
                if ("xl/workbook.xml".equals(entry.getName())) hasWorkbook = true;
                if ("xl/worksheets/sheet1.xml".equals(entry.getName()))
                    worksheet = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertTrue(hasWorkbook);
            assertTrue(worksheet != null && worksheet.contains("Defect ID") && worksheet.contains("Notes"));
            assertTrue(worksheet != null && worksheet.contains("Latch &lt;repaired&gt; &amp; checked"));
        }
    }
}
