package com.localinvoice;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipInvoiceImportServiceTest {
    @TempDir Path temp;

    @Test void scansFoldersAndNestedZipWithoutWritingArchivePathsToDisk() throws Exception {
        AppService app = new AppService(temp.resolve("data"), temp.resolve("without-ocr"));
        byte[] first = pdf("First receipt");
        byte[] second = pdf("Second receipt");
        byte[] nested = zip(Map.of("inside/second.pdf", second));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("folder/first.pdf", first);
        files.put("nested.zip", nested);
        files.put("../../escape.pdf", first);
        Path archive = temp.resolve("batch.zip");
        Files.write(archive, zip(files));

        ZipInvoiceImportService importer = new ZipInvoiceImportService(app, ignored -> true);
        try (var report = importer.importArchives(List.of(archive), ignored -> { })) {
            assertEquals(3, report.entries().size());
            assertEquals(2, report.importedCount());
            assertEquals(ZipInvoiceImportService.Status.IMPORTED, report.entries().get(0).status());
            assertEquals(ZipInvoiceImportService.Status.IMPORTED, report.entries().get(1).status());
            assertEquals(ZipInvoiceImportService.Status.EXISTING, report.entries().get(2).status());
            assertTrue(report.entries().get(1).displayPath().contains("nested.zip / inside/second.pdf"));
            assertEquals(List.of("first.pdf", "second.pdf"), app.invoices().stream()
                    .map(invoice -> invoice.originalName).sorted().toList());
            assertFalse(Files.exists(temp.resolve("escape.pdf")));
        }
    }

    @Test void missedPdfCanBeSelectedForManualImport() throws Exception {
        AppService app = new AppService(temp.resolve("data"), temp.resolve("without-ocr"));
        Path archive = temp.resolve("missed.zip");
        Files.write(archive, zip(Map.of("receipts/missed.pdf", pdf("Plain document"))));
        ZipInvoiceImportService importer = new ZipInvoiceImportService(app);
        try (var report = importer.importArchives(List.of(archive), ignored -> { })) {
            assertEquals(1, report.entries().size());
            var entry = report.entries().getFirst();
            assertEquals(ZipInvoiceImportService.Status.NOT_DETECTED, entry.status());
            assertTrue(entry.canImport());
            assertTrue(Files.isRegularFile(entry.extracted()));
            ImportResult imported = importer.importSelected(entry);
            assertTrue(imported.success(), imported.message());
            assertEquals(ZipInvoiceImportService.Status.IMPORTED, entry.status());
            assertEquals("missed.pdf", app.invoices().getFirst().originalName);
        }
    }

    @Test void recognizesUserInvoiceInsideZipWhenSampleAvailable() throws Exception {
        String encoded = System.getProperty("invoice.sample.path.b64");
        Assumptions.assumeTrue(encoded != null);
        Path sample = Path.of(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        Assumptions.assumeTrue(Files.isRegularFile(sample));
        AppService app = new AppService(temp.resolve("data"), temp.resolve("without-ocr"));
        Path archive = temp.resolve("real.zip");
        Files.write(archive, zip(Map.of("月度/餐饮1.pdf", Files.readAllBytes(sample))));
        try (var report = new ZipInvoiceImportService(app).importArchives(List.of(archive), ignored -> { })) {
            assertEquals(1, report.importedCount());
            assertEquals(ZipInvoiceImportService.Status.IMPORTED, report.entries().getFirst().status());
            Invoice invoice = app.invoices().getFirst();
            assertEquals("餐饮1.pdf", invoice.originalName);
            assertEquals(46800L, invoice.amountCents);
            assertEquals(Category.DINING, invoice.category);
        }
    }

    @Test void autoImportsScannedFlightItineraryFromZipWhenSampleAvailable() throws Exception {
        String encoded = System.getProperty("invoice.air-sample.path.b64");
        Assumptions.assumeTrue(encoded != null);
        Path sample = Path.of(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        Assumptions.assumeTrue(Files.isRegularFile(sample));
        AppService app = new AppService(temp.resolve("data"), Path.of(System.getProperty("user.dir")));
        Path archive = temp.resolve("flights.zip");
        Files.write(archive, zip(Map.of("travel/itinerary.pdf", Files.readAllBytes(sample))));
        try (var report = new ZipInvoiceImportService(app).importArchives(List.of(archive), ignored -> { })) {
            assertEquals(1, report.importedCount());
            assertEquals(ZipInvoiceImportService.Status.IMPORTED, report.entries().getFirst().status());
            Invoice invoice = app.invoices().getFirst();
            assertEquals("itinerary.pdf", invoice.originalName);
            assertEquals(76000L, invoice.amountCents);
            assertEquals(Category.TRANSPORT, invoice.category);
        }
    }

    @Test void keepsProcessedResultsWhenAnotherArchiveIsDamaged() throws Exception {
        AppService app = new AppService(temp.resolve("data"), temp.resolve("without-ocr"));
        Path valid = temp.resolve("valid.zip");
        Path damaged = temp.resolve("damaged.zip");
        Files.write(valid, zip(Map.of("invoice.pdf", pdf("Plain PDF"))));
        Files.writeString(damaged, "not a ZIP archive");
        try (var report = new ZipInvoiceImportService(app, ignored -> true)
                .importArchives(List.of(valid, damaged), ignored -> { })) {
            assertEquals(1, report.importedCount());
            assertEquals(1, report.entries().size());
            assertEquals(1, report.warnings().size());
        }
    }

    @Test void readsChineseNamesFromGbkZip() throws Exception {
        AppService app = new AppService(temp.resolve("data"), temp.resolve("without-ocr"));
        Path archive = temp.resolve("gbk.zip");
        Files.write(archive, zip(Map.of("目录/餐饮发票.pdf", pdf("Plain PDF")), Charset.forName("GBK")));
        try (var report = new ZipInvoiceImportService(app, ignored -> true)
                .importArchives(List.of(archive), ignored -> { })) {
            assertEquals(1, report.importedCount());
            assertEquals("餐饮发票.pdf", app.invoices().getFirst().originalName);
        }
    }

    @Test void detectionRequiresInvoiceSignalsBeyondAnAmount() {
        Invoice ordinary = new Invoice();
        ordinary.amountCents = 46800L;
        assertFalse(ZipInvoiceImportService.likelyInvoice(ordinary));
        ordinary.issuer = "餐饮开票方";
        ordinary.issueDate = LocalDate.of(2026, 9, 12);
        assertTrue(ZipInvoiceImportService.likelyInvoice(ordinary));
        Invoice ticket = new Invoice();
        ticket.amountCents = 17753L;
        ticket.category = Category.TRANSPORT;
        ticket.traveler = "旅客";
        ticket.travelDate = LocalDate.of(2026, 9, 21);
        assertTrue(ZipInvoiceImportService.likelyInvoice(ticket));
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] zip(Map<String, byte[]> files) throws Exception {
        return zip(files, StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> files, Charset charset) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output, charset)) {
            for (var file : files.entrySet()) {
                archive.putNextEntry(new ZipEntry(file.getKey()));
                archive.write(file.getValue());
                archive.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
