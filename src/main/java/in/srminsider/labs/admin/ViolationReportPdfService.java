package in.srminsider.labs.admin;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import in.srminsider.labs.domain.model.AnomalyLog;
import in.srminsider.labs.domain.model.TestSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a student's proctoring record as a PDF an invigilator can file,
 * email, or attach to an academic-integrity case.
 *
 * Built server-side rather than in the browser so the document is identical
 * whoever downloads it, and so it reflects the stored record directly rather
 * than whatever a page happened to have loaded.
 */
@Slf4j
@Service
public class ViolationReportPdfService {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final Color INK = new Color(17, 24, 39);
    private static final Color MUTED = new Color(107, 114, 128);
    private static final Color RULE = new Color(209, 213, 219);
    private static final Color HEADER_BG = new Color(243, 244, 246);
    private static final Color FLAGGED = new Color(185, 28, 28);
    private static final Color CLEAN = new Color(4, 120, 87);

    /**
     * The signals actually emitted during an exam, with readable names.
     *
     * FULLSCREEN_EXIT, FACE_NOT_VISIBLE and DEVTOOLS are accepted by the
     * anomaly schema but nothing currently reports them, so they are left out
     * of the summary: printing a permanent zero beside them would read as
     * "checked and clean" in an integrity case when the truth is "not
     * monitored". Any event that does arrive still appears in the timeline.
     */
    private static final Map<String, String> TYPE_LABELS = new LinkedHashMap<>();
    static {
        TYPE_LABELS.put("TAB_SWITCH", "Tab switches");
        TYPE_LABELS.put("APP_SWITCH", "Switched to another application");
        TYPE_LABELS.put("COPY_PASTE", "Copy / paste attempts");
        TYPE_LABELS.put("SCREENSHOT", "Screenshot attempts");
        TYPE_LABELS.put("FACE_MISMATCH", "Different face detected");
        TYPE_LABELS.put("MULTIPLE_FACES", "Multiple faces in frame");
        TYPE_LABELS.put("LOOKING_AWAY", "Looking away from screen");
        TYPE_LABELS.put("VOICE_DETECTED", "Talking detected");
    }

    private static Font font(float size, boolean bold, Color colour) {
        return FontFactory.getFont(FontFactory.HELVETICA, size,
                bold ? Font.BOLD : Font.NORMAL, colour);
    }

    private static PdfPCell cell(String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", font));
        cell.setHorizontalAlignment(align);
        cell.setPadding(6f);
        cell.setBorderColor(RULE);
        return cell;
    }

    private static PdfPCell headerCell(String text) {
        PdfPCell cell = cell(text, font(9, true, INK), Element.ALIGN_LEFT);
        cell.setBackgroundColor(HEADER_BG);
        return cell;
    }

    public byte[] render(TestSession session, String generatedBy) {
        Document document = new Document(PageSize.A4, 40, 40, 44, 44);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter.getInstance(document, out);
            document.open();

            document.add(titleBlock(session));
            document.add(metaTable(session));
            document.add(spacer(14));

            document.add(sectionHeading("Violation summary"));
            document.add(summaryTable(session));
            document.add(spacer(14));

            document.add(sectionHeading("Event timeline"));
            document.add(timelineTable(session));
            document.add(spacer(16));

            document.add(footer(generatedBy));
            document.close();
        } catch (Exception e) {
            log.error("[Report] Failed to render PDF for {}", session.getUsername(), e);
            throw new IllegalStateException("Could not generate the report PDF.", e);
        }

        return out.toByteArray();
    }

    private Paragraph titleBlock(TestSession session) {
        Paragraph title = new Paragraph("Proctoring Violation Report", font(18, true, INK));
        title.setSpacingAfter(2f);

        Paragraph subtitle = new Paragraph("SRM Insider Labs — automated invigilation record", font(9, false, MUTED));
        subtitle.setSpacingAfter(12f);

        Paragraph block = new Paragraph();
        block.add(title);
        block.add(subtitle);
        return block;
    }

    private PdfPTable metaTable(TestSession session) throws Exception {
        PdfPTable table = new PdfPTable(new float[]{1.1f, 2.4f, 1.1f, 2.4f});
        table.setWidthPercentage(100);

        String status = session.isBlocked() ? "BLOCKED"
                : (session.isSubmitted() ? (session.isAutoSubmitted() ? "Auto-submitted" : "Submitted")
                                         : "In progress");

        table.addCell(headerCell("Student"));
        table.addCell(cell(session.getUsername(), font(9, false, INK), Element.ALIGN_LEFT));
        table.addCell(headerCell("Status"));
        table.addCell(cell(status, font(9, true, session.isBlocked() ? FLAGGED : INK), Element.ALIGN_LEFT));

        table.addCell(headerCell("Year"));
        table.addCell(cell(session.getYear() != null ? String.valueOf(session.getYear()) : "—",
                font(9, false, INK), Element.ALIGN_LEFT));
        table.addCell(headerCell("Domain"));
        table.addCell(cell(session.getDomain(), font(9, false, INK), Element.ALIGN_LEFT));

        table.addCell(headerCell("Started"));
        table.addCell(cell(session.getCreatedAt() > 0 ? TIMESTAMP.format(Instant.ofEpochMilli(session.getCreatedAt())) : "—",
                font(9, false, INK), Element.ALIGN_LEFT));
        table.addCell(headerCell("Submitted"));
        table.addCell(cell(session.getSubmittedAt() > 0 ? TIMESTAMP.format(Instant.ofEpochMilli(session.getSubmittedAt())) : "—",
                font(9, false, INK), Element.ALIGN_LEFT));

        return table;
    }

    private Paragraph sectionHeading(String text) {
        Paragraph heading = new Paragraph(text, font(12, true, INK));
        heading.setSpacingAfter(6f);
        return heading;
    }

    private PdfPTable summaryTable(TestSession session) throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AnomalyLog anomaly : session.getAnomalies()) {
            if (anomaly.getType() == null) continue;
            counts.merge(anomaly.getType(), 1L, Long::sum);
        }

        PdfPTable table = new PdfPTable(new float[]{3.2f, 1f});
        table.setWidthPercentage(100);
        table.addCell(headerCell("Violation type"));
        PdfPCell countHeader = headerCell("Count");
        countHeader.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(countHeader);

        // Every known signal is listed, including the zeroes: "no screenshots
        // were detected" is itself a finding in an integrity case, and an
        // absent row would read as untested rather than clean.
        for (Map.Entry<String, String> entry : TYPE_LABELS.entrySet()) {
            long count = counts.getOrDefault(entry.getKey(), 0L);
            boolean flagged = count > 0;
            table.addCell(cell(entry.getValue(), font(9, flagged, flagged ? FLAGGED : MUTED), Element.ALIGN_LEFT));
            table.addCell(cell(String.valueOf(count),
                    font(9, true, flagged ? FLAGGED : MUTED), Element.ALIGN_RIGHT));
        }

        table.addCell(cell("Total recorded events", font(9, true, INK), Element.ALIGN_LEFT));
        table.addCell(cell(String.valueOf(session.getAnomalies().size()),
                font(9, true, session.getAnomalies().isEmpty() ? CLEAN : FLAGGED), Element.ALIGN_RIGHT));

        return table;
    }

    private PdfPTable timelineTable(TestSession session) throws Exception {
        PdfPTable table = new PdfPTable(new float[]{1.5f, 1.5f, 3.4f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);

        table.addCell(headerCell("Time"));
        table.addCell(headerCell("Type"));
        table.addCell(headerCell("Detail"));

        List<AnomalyLog> anomalies = session.getAnomalies().stream()
                .sorted(Comparator.comparingLong(AnomalyLog::getTimestamp))
                .toList();

        if (anomalies.isEmpty()) {
            PdfPCell empty = cell("No violations were recorded during this exam.",
                    font(9, false, CLEAN), Element.ALIGN_CENTER);
            empty.setColspan(3);
            empty.setPadding(14f);
            table.addCell(empty);
            return table;
        }

        for (AnomalyLog anomaly : anomalies) {
            table.addCell(cell(TIMESTAMP.format(Instant.ofEpochMilli(anomaly.getTimestamp())),
                    font(8, false, MUTED), Element.ALIGN_LEFT));
            table.addCell(cell(TYPE_LABELS.getOrDefault(anomaly.getType(), anomaly.getType()),
                    font(8, true, INK), Element.ALIGN_LEFT));
            table.addCell(cell(anomaly.getDescription(), font(8, false, INK), Element.ALIGN_LEFT));
        }

        return table;
    }

    private Paragraph spacer(float height) {
        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingAfter(height);
        return spacer;
    }

    private Paragraph footer(String generatedBy) {
        Paragraph note = new Paragraph(
                "Generated " + TIMESTAMP.format(Instant.now()) + " by " + generatedBy + ".",
                font(8, false, MUTED));
        note.setSpacingAfter(3f);

        Paragraph caveat = new Paragraph(
                "These are automated detections, not conclusions. Browser-based proctoring cannot observe "
                        + "activity outside the exam window — a second device, a person off-camera, or an overlay "
                        + "that never takes focus will not appear here. Review the recorded evidence before acting.",
                font(7.5f, false, MUTED));

        Paragraph block = new Paragraph();
        block.add(note);
        block.add(caveat);
        return block;
    }
}
