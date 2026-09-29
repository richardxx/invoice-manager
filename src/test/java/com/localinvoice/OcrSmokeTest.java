package com.localinvoice;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OcrSmokeTest {
    @TempDir Path temp;

    @Test void localOcrReadsSyntheticRailwayScan() throws Exception {
        Path project = Path.of(System.getProperty("user.dir"));
        Assumptions.assumeTrue(Files.isRegularFile(project.resolve("tessdata/chi_sim.traineddata")));
        Path pdf = temp.resolve("rail-scan.pdf");
        BufferedImage image = new BufferedImage(1500, 1100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("Microsoft YaHei", Font.PLAIN, 54));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        String[] lines = {"电子发票 铁路电子客票", "销售方名称 中国铁路", "开票日期 2026年09月10日",
                "乘车日期 2026年09月08日", "票价 238.50"};
        int y = 170;
        for (String line : lines) { graphics.drawString(line, 90, y); y += 160; }
        graphics.dispose();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(750, 550));
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, 750, 550);
            }
            document.save(pdf.toFile());
        }
        Invoice invoice = new RecognitionService(project).recognize(pdf);
        assertEquals("OCR", invoice.recognitionSource);
        assertEquals(Category.TRANSPORT, invoice.category);
        assertEquals(23850L, invoice.amountCents);
        assertEquals(java.time.LocalDate.of(2026, 9, 10), invoice.issueDate);
        assertEquals(ReviewStatus.NEEDS_REVIEW, invoice.reviewStatus);
    }
}
