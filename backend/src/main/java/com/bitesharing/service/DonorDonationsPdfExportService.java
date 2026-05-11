package com.bitesharing.service;

import com.bitesharing.model.Donation;
import com.bitesharing.model.User;
import com.lowagie.text.Document;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class DonorDonationsPdfExportService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public byte[] buildPdf(User donor, List<Donation> donations) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4.rotate(), 36, 36, 48, 36);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            doc.add(new Paragraph("ShareBite — donation record", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16)));
            doc.add(new Paragraph(
                    "Donor: " + safe(donor.getFullName(), 120) + "  |  " + safe(donor.getEmail(), 120),
                    FontFactory.getFont(FontFactory.HELVETICA, 10)));
            doc.add(new Paragraph(
                    "Generated: " + DT.format(LocalDateTime.now()),
                    FontFactory.getFont(FontFactory.HELVETICA, 9)));
            doc.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(9);
            table.setWidthPercentage(100);
            table.setWidths(new float[] { 0.55f, 1.35f, 1.0f, 0.55f, 1.1f, 0.85f, 0.85f, 1.15f, 1.1f });

            addHeader(table, "ID");
            addHeader(table, "Food");
            addHeader(table, "Description");
            addHeader(table, "Qty");
            addHeader(table, "Expiry");
            addHeader(table, "Type");
            addHeader(table, "Status");
            addHeader(table, "AI freshness");
            addHeader(table, "Created");

            if (donations == null || donations.isEmpty()) {
                PdfPCell empty = new PdfPCell(new Paragraph("No donations on file.", FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9)));
                empty.setColspan(9);
                empty.setPadding(8);
                table.addCell(empty);
            } else {
                for (Donation d : donations) {
                    addBody(table, d.getId() == null ? "—" : String.valueOf(d.getId()));
                    addBody(table, safe(d.getFoodName(), 48));
                    addBody(table, safe(d.getDescription(), 56));
                    addBody(table, d.getQuantity() == null ? "—" : String.valueOf(d.getQuantity()));
                    addBody(table, d.getExpiryDate() == null ? "—" : DT.format(d.getExpiryDate()));
                    addBody(table, d.getDonationType() == null ? "—" : d.getDonationType().name());
                    addBody(table, d.getStatus() == null ? "—" : d.getStatus().name());
                    String ai = d.getMlFreshnessStatus() == null
                            ? "—"
                            : d.getMlFreshnessStatus()
                                    + (d.getMlFreshnessConfidence() != null
                                    ? " (" + String.format("%.0f%%", d.getMlFreshnessConfidence() * 100.0) + ")"
                                    : "");
                    addBody(table, ai);
                    addBody(table, d.getCreatedAt() == null ? "—" : DT.format(d.getCreatedAt()));
                }
            }

            doc.add(table);
            doc.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Donor donations PDF failed: " + e.getMessage(), e);
        }
    }

    private static void addHeader(PdfPTable table, String text) {
        PdfPCell c = new PdfPCell(new Paragraph(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8)));
        c.setBackgroundColor(new Color(235, 235, 235));
        c.setPadding(4);
        table.addCell(c);
    }

    private static void addBody(PdfPTable table, String text) {
        PdfPCell c = new PdfPCell(new Paragraph(text == null ? "—" : text, FontFactory.getFont(FontFactory.HELVETICA, 7)));
        c.setPadding(3);
        table.addCell(c);
    }

    private static String safe(String s, int max) {
        if (s == null || s.isBlank()) {
            return "—";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
