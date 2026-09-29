package com.localinvoice;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RealInvoiceRecognitionTest {
    @TempDir Path temp;

    @Test void recognizesUserProvidedRestaurantInvoiceWhenAvailable() throws Exception {
        String encoded = System.getProperty("invoice.sample.path.b64");
        Assumptions.assumeTrue(encoded != null);
        Path sample = Path.of(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        Assumptions.assumeTrue(Files.isRegularFile(sample));
        Invoice invoice = new RecognitionService(temp).recognize(sample);
        assertNotNull(invoice.issuer);
        assertTrue(invoice.issuer.contains("餐饮管理"));
        assertNotNull(invoice.buyer);
        assertNotEquals(invoice.buyer, invoice.issuer);
        assertNotNull(invoice.amountCents);
        assertTrue(invoice.amountCents > 0);
        assertNotNull(invoice.issueDate);
        assertNotNull(invoice.invoiceNumber);
        assertTrue(invoice.invoiceNumber.matches("\\d{20}"));
        assertEquals(Category.DINING, invoice.category);
        Invoice forcedOcr = new RecognitionService(Path.of(System.getProperty("user.dir"))).recognize(sample, true);
        assertNotNull(forcedOcr.issuer);
        assertTrue(forcedOcr.issuer.contains("餐饮管理"));
        assertEquals(invoice.issuer, forcedOcr.issuer);
        assertEquals(invoice.amountCents, forcedOcr.amountCents);
    }

    @Test void recognizesUserProvidedScannedFlightItineraryWhenAvailable() throws Exception {
        String encoded = System.getProperty("invoice.air-sample.path.b64");
        Assumptions.assumeTrue(encoded != null);
        Path sample = Path.of(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        Assumptions.assumeTrue(Files.isRegularFile(sample));
        Invoice invoice = new RecognitionService(Path.of(System.getProperty("user.dir"))).recognize(sample);
        assertEquals("OCR", invoice.recognitionSource);
        assertNotNull(invoice.issuer);
        assertTrue(invoice.issuer.contains("航空"));
        assertNotNull(invoice.invoiceNumber);
        assertTrue(invoice.invoiceNumber.matches("\\d{20}"));
        assertNotNull(invoice.amountCents);
        assertTrue(invoice.amountCents > 0);
        assertNotNull(invoice.issueDate);
        assertNotNull(invoice.travelDate);
        assertEquals(Category.TRANSPORT, invoice.category);
        assertEquals(ReviewStatus.NEEDS_REVIEW, invoice.reviewStatus);
        assertTrue(ZipInvoiceImportService.likelyInvoice(invoice));
    }

    @Test void recognizesSellerFromHighlightedStandardInvoiceScreenshotWhenAvailable() throws Exception {
        String encoded = System.getProperty("invoice.seller-screenshot.path.b64");
        Assumptions.assumeTrue(encoded != null);
        Path screenshot = Path.of(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        Assumptions.assumeTrue(Files.isRegularFile(screenshot));
        BufferedImage image = ImageIO.read(screenshot.toFile());
        Path pdf = temp.resolve("scanned-standard-invoice.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(image.getWidth() / 2f, image.getHeight() / 2f));
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(LosslessFactory.createFromImage(document, image), 0, 0,
                        page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            document.save(pdf.toFile());
        }
        Invoice recognized = new RecognitionService(Path.of(System.getProperty("user.dir"))).recognize(pdf, true);
        assertNotNull(recognized.issuer);
        assertTrue(recognized.issuer.contains("餐饮管理"));
        assertNotEquals(recognized.buyer, recognized.issuer);
        assertEquals(ReviewStatus.NEEDS_REVIEW, recognized.reviewStatus);
    }
}
