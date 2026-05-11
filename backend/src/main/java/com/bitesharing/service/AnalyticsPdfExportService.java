package com.bitesharing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.Document;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class AnalyticsPdfExportService {

    private final ObjectMapper objectMapper;

    /**
     * Deterministic SHA-256 over canonical JSON of the report payload — supports audit / provenance claims.
     */
    public byte[] toPdf(Map<String, Object> data, String title) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(new TreeMap<>(data));
            String digest = sha256Hex(canonical);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document document = new Document();
            PdfWriter.getInstance(document, baos);
            document.open();
            document.add(new Paragraph(title, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16)));
            document.add(new Paragraph("Generated: " + LocalDateTime.now(), FontFactory.getFont(FontFactory.HELVETICA, 10)));
            document.add(new Paragraph(" "));
            for (Map.Entry<String, Object> e : data.entrySet()) {
                String line = e.getKey() + ": " + stringify(e.getValue());
                document.add(new Paragraph(line, FontFactory.getFont(FontFactory.HELVETICA, 9)));
            }
            document.add(new Paragraph(" "));
            document.add(new Paragraph(
                    "ShareBite report integrity digest (SHA-256): " + digest,
                    FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8)));
            document.close();
            return baos.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("PDF build failed: " + ex.getMessage(), ex);
        }
    }

    private static String sha256Hex(byte[] input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(input));
    }

    private String stringify(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }
}
