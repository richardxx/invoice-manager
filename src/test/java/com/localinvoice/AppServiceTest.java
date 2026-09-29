package com.localinvoice;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppServiceTest {
    @TempDir Path temp;

    @Test void importAssignmentMonthMoveUnassignAndDuplicate() throws Exception {
        AppService app = app();
        Path source = pdf("invoice-one.pdf", "Synthetic one");
        ImportResult first = app.importPdf(source);
        assertTrue(first.success(), first.message());
        assertEquals(ReviewStatus.NEEDS_REVIEW, first.invoice().reviewStatus);
        assertFalse(app.importPdf(source).success());
        Files.delete(source);
        assertTrue(Files.isRegularFile(app.original(first.invoice())));

        Person person = app.addPerson("张三", null);
        Invoice invoice = complete(app, first.invoice(), YearMonth.of(2026, 9), 12345);
        app.assign(invoice.id, person.id());
        assertEquals(12345L, app.totals(YearMonth.of(2026, 9)).get(person.id())[Category.TRANSPORT.ordinal()]);
        assertEquals(List.of(person.id()), app.activePeople(YearMonth.of(2026, 9)).stream().map(Person::id).toList());
        assertTrue(app.activePeople(YearMonth.of(2026, 10)).isEmpty());
        invoice = app.invoice(invoice.id);
        invoice.category = Category.DINING;
        invoice.reimbursementMonth = YearMonth.of(2026, 10);
        app.saveInvoice(invoice, false);
        assertEquals(0L, app.totals(YearMonth.of(2026, 9)).get(person.id())[Category.TRANSPORT.ordinal()]);
        assertEquals(12345L, app.totals(YearMonth.of(2026, 10)).get(person.id())[Category.DINING.ordinal()]);
        assertTrue(app.activePeople(YearMonth.of(2026, 9)).isEmpty());
        assertEquals(List.of(person.id()), app.activePeople(YearMonth.of(2026, 10)).stream().map(Person::id).toList());
        app.unassign(invoice.id);
        assertEquals(0L, app.totals(YearMonth.of(2026, 10)).get(person.id())[Category.DINING.ordinal()]);
        assertTrue(app.activePeople(YearMonth.of(2026, 10)).isEmpty());
        assertEquals(person.id(), app.recentOwners().getFirst().id());
    }

    @Test void exportUsesPinyinAndCollisionSuffixWithOriginalBytes() throws Exception {
        AppService app = app();
        Person person = app.addPerson("张三", null);
        ImportResult a = app.importPdf(pdf("a.pdf", "A"));
        ImportResult b = app.importPdf(pdf("b.pdf", "B"));
        assertTrue(a.success(), a.message());
        assertTrue(b.success(), b.message());
        for (Invoice invoice : List.of(a.invoice(), b.invoice())) {
            complete(app, invoice, YearMonth.of(2026, 9), 12345);
            app.assign(invoice.id, person.id());
        }
        YearMonth month = YearMonth.of(2026, 9);
        assertEquals(2, new ExportService(app).exportMonth(month, temp));
        Path folder = ExportService.monthFolder(month, temp);
        try (var files = Files.list(folder)) { assertEquals(2, files.count()); }
        assertTrue(Files.isRegularFile(folder.resolve("zhangsan-交通-123.45.pdf")));
        assertTrue(Files.isRegularFile(folder.resolve("zhangsan-交通-123.45-2.pdf")));
        byte[] exported = Files.readAllBytes(folder.resolve("zhangsan-交通-123.45.pdf"));
        assertTrue(java.util.Arrays.equals(exported, Files.readAllBytes(app.original(a.invoice())))
                || java.util.Arrays.equals(exported, Files.readAllBytes(app.original(b.invoice()))));
        ExportService exporter = new ExportService(app);
        List<ExportService.ExportItem> preview = exporter.previewMonth(YearMonth.of(2026, 9));
        assertEquals(2, preview.size());
        assertEquals("张三", preview.getFirst().ownerName());
        assertEquals(12345L, preview.getFirst().amountCents());
        assertEquals(Category.TRANSPORT, preview.getFirst().category());
        Path duplicate = temp.resolve("duplicate-names");
        assertThrows(IllegalArgumentException.class, () -> exporter.exportMonth(YearMonth.of(2026, 9), duplicate,
                List.of(preview.getFirst().withFileName("Same.pdf"), preview.getLast().withFileName("same.PDF"))));
        assertFalse(Files.exists(duplicate));
    }

    @Test void exportPreviewWritesEditedNameAndRejectsUnsafeOrStalePlans() throws Exception {
        AppService app = app();
        Person owner = app.addPerson("李四", null);
        ImportResult imported = app.importPdf(pdf("ticket.pdf", "Ticket"));
        YearMonth month = YearMonth.of(2026, 9);
        Invoice invoice = complete(app, imported.invoice(), month, 76000);
        app.assign(invoice.id, owner.id());
        ExportService exporter = new ExportService(app);
        ExportService.ExportItem item = exporter.previewMonth(month).getFirst();
        assertEquals("lisi-交通-760.00.pdf", item.fileName());
        for (String unsafe : List.of("../ticket.pdf", "NUL.pdf", "ticket.txt", "bad?.pdf", "ticket..pdf"))
            assertThrows(IllegalArgumentException.class,
                    () -> ExportService.validateFileNames(List.of(item.withFileName(unsafe))));

        Path exportParent = Files.createDirectory(temp.resolve("edited"));
        assertEquals(1, exporter.exportMonth(month, exportParent, List.of(item.withFileName("机票报销.pdf"))));
        Path folder = ExportService.monthFolder(month, exportParent);
        assertArrayEquals(Files.readAllBytes(app.original(invoice)), Files.readAllBytes(folder.resolve("机票报销.pdf")));
        assertThrows(IllegalArgumentException.class,
                () -> exporter.exportMonth(month, exportParent, List.of(item.withFileName("机票报销.pdf"))));

        Invoice changed = app.invoice(invoice.id);
        changed.amountCents = 80000L;
        app.saveInvoice(changed, false);
        Path stale = temp.resolve("stale");
        assertThrows(IllegalStateException.class,
                () -> exporter.exportMonth(month, stale, List.of(item.withFileName("another.pdf"))));
        assertFalse(Files.exists(stale));
    }

    @Test void backupRestoresDataAndRejectsCorruption() throws Exception {
        AppService app = app();
        Person person = app.addPerson("李四", null);
        ImportResult imported = app.importPdf(pdf("backup.pdf", "Back me up"));
        assertTrue(imported.success(), imported.message());
        complete(app, imported.invoice(), YearMonth.of(2026, 9), 10000);
        app.assign(imported.invoice().id, person.id());
        Path archive = temp.resolve("backup.limbackup");
        assertEquals(1, new BackupService(app).backup(archive));
        app.addPerson("后来新增", null);
        new BackupService(app).restore(archive);
        assertEquals(2, app.people().size());
        assertEquals(1, app.invoices().size());
        assertEquals(10000L, app.totals(YearMonth.of(2026, 9)).get(person.id())[Category.TRANSPORT.ordinal()]);
        assertTrue(Files.isRegularFile(app.original(app.invoices().getFirst())));
        assertTrue(app.importPdf(pdf("after-restore.pdf", "After restore")).success());

        Path damaged = temp.resolve("damaged.limbackup");
        Files.writeString(damaged, "bad archive");
        assertThrows(Exception.class, () -> new BackupService(app).restore(damaged));
        assertEquals(2, app.invoices().size());
    }

    @Test void semanticDuplicateRequiresReasonAndRecentOwnersStayDistinct() throws Exception {
        AppService app = app();
        ImportResult imported = app.importPdf(pdf("original.pdf", "Original"));
        assertTrue(imported.success(), imported.message());
        Invoice original = complete(app, imported.invoice(), YearMonth.of(2026, 9), 5000);
        original.invoiceNumber = "20260927000000000001";
        app.saveInvoice(original, false);
        Invoice candidate = new Invoice();
        candidate.invoiceNumber = original.invoiceNumber;
        candidate.issueDate = original.issueDate;
        candidate.amountCents = original.amountCents;
        assertEquals(original.id, app.database().findSemanticDuplicate(candidate).id);

        original.duplicateOf = "possible-existing-id";
        original.manuallyEdited = null;
        assertThrows(IllegalArgumentException.class, () -> app.saveInvoice(original, true));
        original.manuallyEdited = "Different journey with confirmed invoice number";
        app.saveInvoice(original, true);

        Person first = null;
        for (int index = 0; index < 5; index++) {
            Person person = app.addPerson("测试人员" + index, null);
            if (first == null) first = person;
            app.database().rememberOwner(person.id());
        }
        String firstId = first.id();
        assertEquals(4, app.recentOwners().size());
        assertFalse(app.recentOwners().stream().anyMatch(person -> person.id().equals(firstId)));
        app.database().rememberOwner(app.recentOwners().getLast().id());
        assertEquals(4, app.recentOwners().size());
    }

    @Test void companyAccountCannotBeRenamedOrDeleted() throws Exception {
        AppService app = app();
        Person company = app.people().stream().filter(Person::company).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> app.updatePerson(company, "新名称", null));
        assertThrows(IllegalArgumentException.class, () -> app.deletePerson(company.id()));
        assertEquals("公司", app.database().person(company.id()).name());
    }

    @Test void fourPendingAssignedInvoicesKeepPersonVisibleInMonth() throws Exception {
        AppService app = app();
        Person owner = app.addPerson("张三", "preset:sky");
        YearMonth month = YearMonth.of(2026, 9);
        for (int index = 0; index < 4; index++) {
            ImportResult imported = app.importPdf(pdf("pending-" + index + ".pdf", "Pending " + index));
            assertTrue(imported.success(), imported.message());
            assertEquals(ReviewStatus.NEEDS_REVIEW, imported.invoice().reviewStatus);
            app.assign(imported.invoice().id, owner.id(), month);
        }
        assertEquals(4, app.assignedInMonth(month).size());
        assertEquals(List.of(owner.id()), app.activePeople(month).stream().map(Person::id).toList());
        assertTrue(app.countedInMonth(month).isEmpty());
        assertEquals(0L, app.totals(month).get(owner.id())[Category.TRANSPORT.ordinal()]);
    }

    @Test void monthlyDisplayIncludesKnownPendingAmountsButExportStillRequiresReview() throws Exception {
        AppService app = app();
        Person owner = app.addPerson("金额测试", null);
        YearMonth month = YearMonth.of(2026, 9);
        ImportResult dining = app.importPdf(pdf("dining.pdf", "Dining pending"));
        ImportResult travel = app.importPdf(pdf("travel.pdf", "Travel confirmed"));
        Invoice pending = complete(app, dining.invoice(), month, 46800);
        pending.category = Category.DINING;
        pending.reviewStatus = ReviewStatus.NEEDS_REVIEW;
        app.saveInvoice(pending, false);
        Invoice confirmed = complete(app, travel.invoice(), month, 17753);
        app.assign(pending.id, owner.id(), month);
        app.assign(confirmed.id, owner.id(), month);

        long[] displayed = app.displayTotals(month).get(owner.id());
        assertEquals(46800L, displayed[Category.DINING.ordinal()]);
        assertEquals(17753L, displayed[Category.TRANSPORT.ordinal()]);
        assertEquals(0L, app.totals(month).get(owner.id())[Category.DINING.ordinal()]);
        assertEquals(1, app.countedInMonth(month).size());
        assertEquals(1, new ExportService(app).exportMonth(month, temp));
        try (var files = Files.list(ExportService.monthFolder(month, temp))) {
            assertEquals(1, files.count());
        }
    }

    @Test void bulkAssignmentIsAtomicAndUsesOneReimbursementMonth() throws Exception {
        AppService app = app();
        Person owner = app.addPerson("批量归属", null);
        ImportResult first = app.importPdf(pdf("bulk-a.pdf", "Bulk A"));
        ImportResult second = app.importPdf(pdf("bulk-b.pdf", "Bulk B"));
        assertTrue(first.success(), first.message());
        assertTrue(second.success(), second.message());
        YearMonth month = YearMonth.of(2026, 11);
        assertThrows(IllegalArgumentException.class,
                () -> app.assignMany(List.of(first.invoice().id, "missing"), owner.id(), month));
        assertNull(app.invoice(first.invoice().id).ownerId);
        assertNull(app.invoice(second.invoice().id).ownerId);

        app.assignMany(List.of(first.invoice().id, second.invoice().id), owner.id(), month);
        for (Invoice invoice : app.invoices()) {
            assertEquals(owner.id(), invoice.ownerId);
            assertEquals(month, invoice.reimbursementMonth);
        }
        assertEquals(2, app.assignedInMonth(month).size());
        assertEquals(owner.id(), app.recentOwners().getFirst().id());
    }

    @Test void configuredAiIsNeverCalledDuringImport() throws Exception {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            AppService app = app();
            app.settings().save(new AiSettings(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "vision-test", "secret"));
            ImportResult result = app.importPdf(pdf("local-import.pdf", "Local sample"));
            assertTrue(result.success(), result.message());
            assertEquals("PDF_TEXT", result.invoice().recognitionSource);
            assertNull(result.invoice().reimbursementMonth);
            assertTrue(Files.isRegularFile(app.original(result.invoice())));
            assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }

    @Test void aiRerecognitionPreservesOwnerAndChosenMonth() throws Exception {
        AppService app = app();
        Person person = app.addPerson("测试归属", "preset:sky");
        ImportResult imported = app.importPdf(pdf("rerecognize.pdf", "Existing invoice"));
        Invoice original = complete(app, imported.invoice(), YearMonth.of(2026, 10), 5000);
        app.assign(original.id, person.id());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String result = "{\"issuer\":\"新的开票方\",\"amount\":\"65.00\","
                    + "\"issueDate\":\"2026-09-01\",\"category\":\"DINING\"}";
            byte[] payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(
                    java.util.Map.of("choices", List.of(java.util.Map.of("message",
                            java.util.Map.of("content", result)))));
            exchange.sendResponseHeaders(200, payload.length);
            try (var body = exchange.getResponseBody()) { body.write(payload); }
        });
        server.start();
        try {
            app.settings().save(new AiSettings(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "vision-test", "secret"));
            Invoice updated = app.reRecognize(original.id);
            assertEquals("新的开票方", updated.issuer);
            assertEquals(6500L, updated.amountCents);
            assertEquals(Category.DINING, updated.category);
            assertEquals(person.id(), updated.ownerId);
            assertEquals(YearMonth.of(2026, 10), updated.reimbursementMonth);
            assertEquals(ReviewStatus.NEEDS_REVIEW, updated.reviewStatus);
            assertEquals(List.of(person.id()), app.activePeople(YearMonth.of(2026, 10))
                    .stream().map(Person::id).toList());
        } finally { server.stop(0); }
    }

    @Test void reimbursementAndDeletionPersistAndProtectPaidInvoices() throws Exception {
        AppService app = app();
        Person owner = app.addPerson("报销测试", null);
        ImportResult imported = app.importPdf(pdf("paid.pdf", "Paid invoice"));
        assertTrue(imported.success(), imported.message());
        Invoice invoice = complete(app, imported.invoice(), YearMonth.of(2026, 9), 3000);
        app.assign(invoice.id, owner.id(), YearMonth.of(2026, 9));
        assertFalse(app.monthCompleted(YearMonth.of(2026, 9)));
        assertEquals(1, app.setMonthCompleted(YearMonth.of(2026, 9), true));

        AppService reopened = app();
        assertTrue(reopened.monthCompleted(YearMonth.of(2026, 9)));
        assertNotNull(reopened.invoice(invoice.id).reimbursedAt);
        assertThrows(IllegalStateException.class, () -> reopened.deleteInvoice(invoice.id));
        assertThrows(IllegalStateException.class, () -> reopened.unassign(invoice.id));
        assertThrows(IllegalStateException.class,
                () -> reopened.assign(invoice.id, owner.id(), YearMonth.of(2026, 10)));
        assertEquals(1, reopened.setMonthCompleted(YearMonth.of(2026, 9), false));
        assertNull(reopened.invoice(invoice.id).reimbursedAt);

        reopened.deleteInvoice(invoice.id);
        assertNull(reopened.invoice(invoice.id));
        assertTrue(reopened.invoices().isEmpty());
        assertTrue(Files.isRegularFile(reopened.original(invoice)));
        ImportResult restored = reopened.importPdf(temp.resolve("paid.pdf"));
        assertTrue(restored.success(), restored.message());
        assertEquals(invoice.id, restored.invoice().id);
        assertNull(restored.invoice().ownerId);
        assertNull(restored.invoice().reimbursementMonth);
    }

    @Test void completingEmptyMonthDoesNotCreatePaidState() throws Exception {
        AppService app = app();
        YearMonth month = YearMonth.of(2026, 11);
        assertThrows(IllegalArgumentException.class, () -> app.setMonthCompleted(month, true));
        assertFalse(app.monthCompleted(month));
    }

    private AppService app() throws Exception {
        return new AppService(temp.resolve("data"), temp.resolve("project-without-ocr"));
    }

    private Invoice complete(AppService app, Invoice invoice, YearMonth month, long cents) throws Exception {
        Invoice current = app.invoice(invoice.id);
        current.issuer = "测试开票方";
        current.issueDate = LocalDate.of(2026, 8, 1);
        current.amountCents = cents;
        current.category = Category.TRANSPORT;
        current.reimbursementMonth = month;
        app.saveInvoice(current, true);
        return current;
    }

    private Path pdf(String name, String content) throws IOException {
        Path path = temp.resolve(name);
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(content);
                stream.endText();
            }
            document.save(path.toFile());
        }
        return path;
    }
}
