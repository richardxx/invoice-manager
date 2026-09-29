package com.localinvoice;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.UUID;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BankExcelExportServiceTest {
    @TempDir Path temp;

    @Test void fillsOnlyRequestedTemplateSheetWithOneRowPerPersonAndExactTextIdentifiers() throws Exception {
        AppService app = new AppService(temp.resolve("data"), temp);
        PaymentDetails first = new PaymentDetails("001234567890", "张三", PaymentDetails.BankType.OTHER,
                "012345678901", "居民身份证", "012345678901234567");
        PaymentDetails second = new PaymentDetails("009876543210", "李四", PaymentDetails.BankType.BOC,
                "098765432109", "居民身份证", "987654321098765432");
        Person zhang = app.addPerson("张三", null, first);
        Person li = app.addPerson("李四", null, second);
        YearMonth month = YearMonth.of(2026, 9);
        insert(app, "one", zhang, month, 12345, ReviewStatus.READY);
        insert(app, "two", zhang, month, 500, ReviewStatus.READY);
        insert(app, "pending", zhang, month, 999, ReviewStatus.NEEDS_REVIEW);
        insert(app, "three", li, month, 76000, ReviewStatus.READY);

        BankExcelExportService exporter = new BankExcelExportService(app);
        assertEquals(2, exporter.previewMonth(month).size());
        Path output = temp.resolve("bank.xls");
        assertEquals(2, exporter.exportMonth(month, output));
        try (var in = Files.newInputStream(output); var book = new HSSFWorkbook(in)) {
            assertEquals(2, book.getNumberOfSheets());
            var sheet = book.getSheet("其他代发批量文件-混合");
            assertNotNull(sheet);
            assertEquals("卡号/账号", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(2, sheet.getLastRowNum());
            var firstRow = sheet.getRow(1);
            var secondRow = sheet.getRow(2);
            var liRow = "李四".equals(firstRow.getCell(1).getStringCellValue()) ? firstRow : secondRow;
            var zhangRow = liRow == firstRow ? secondRow : firstRow;
            assertEquals("009876543210", liRow.getCell(0).getStringCellValue());
            assertEquals("李四", liRow.getCell(1).getStringCellValue());
            assertEquals(760.00, liRow.getCell(2).getNumericCellValue(), 0.001);
            assertEquals("中行", liRow.getCell(3).getStringCellValue());
            assertEquals("001234567890", zhangRow.getCell(0).getStringCellValue());
            assertEquals(128.45, zhangRow.getCell(2).getNumericCellValue(), 0.001);
            assertEquals("他行", zhangRow.getCell(3).getStringCellValue());
            assertEquals("012345678901", zhangRow.getCell(4).getStringCellValue());
            assertEquals("居民身份证", zhangRow.getCell(5).getStringCellValue());
            assertEquals(CellType.STRING, zhangRow.getCell(6).getCellType());
            assertEquals("012345678901234567", zhangRow.getCell(6).getStringCellValue());
            assertEquals("报销", zhangRow.getCell(7).getStringCellValue());
            assertEquals("2026-09 报销", zhangRow.getCell(8).getStringCellValue());
        }
        assertEquals(0L, app.completedTotals().getOrDefault(zhang.id(), 0L));
        app.setMonthCompleted(month, true);
        assertEquals(13844L, app.completedTotals().get(zhang.id()));
    }

    @Test void missingPaymentDetailsBlockExportWithoutCreatingAFile() throws Exception {
        AppService app = new AppService(temp.resolve("data"), temp);
        Person person = app.addPerson("旧人员", null);
        insert(app, "old", person, YearMonth.of(2026, 9), 10000, ReviewStatus.READY);
        Path output = temp.resolve("blocked.xls");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new BankExcelExportService(app).exportMonth(YearMonth.of(2026, 9), output));
        assertTrue(error.getMessage().contains("旧人员"));
        assertFalse(Files.exists(output));
    }

    @Test void excludesCompanyFromBankFileWhileKeepingItsReimbursement() throws Exception {
        AppService app = new AppService(temp.resolve("data"), temp);
        Person company = app.people().stream().filter(Person::company).findFirst().orElseThrow();
        PaymentDetails payment = new PaymentDetails("001234567890", "张三", PaymentDetails.BankType.OTHER,
                "012345678901", "居民身份证", "012345678901234567");
        Person person = app.addPerson("张三", null, payment);
        YearMonth month = YearMonth.of(2026, 9);
        insert(app, "company", company, month, 50000, ReviewStatus.READY);
        insert(app, "person", person, month, 12345, ReviewStatus.READY);

        BankExcelExportService exporter = new BankExcelExportService(app);
        var preview = exporter.previewMonth(month);
        assertEquals(1, preview.size());
        assertEquals(person.id(), preview.getFirst().person().id());
        assertEquals(12345, preview.getFirst().amountCents());
        Path output = temp.resolve("personal-only.xls");
        assertEquals(1, exporter.exportMonth(month, output));
        try (var in = Files.newInputStream(output); var book = new HSSFWorkbook(in)) {
            var sheet = book.getSheet("其他代发批量文件-混合");
            assertEquals(1, sheet.getLastRowNum());
            assertEquals("张三", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals(123.45, sheet.getRow(1).getCell(2).getNumericCellValue(), 0.001);
        }
        app.setMonthCompleted(month, true);
        assertEquals(50000L, app.completedTotals().get(company.id()));
    }

    @Test void companyOnlyMonthDoesNotCreateBankFile() throws Exception {
        AppService app = new AppService(temp.resolve("data"), temp);
        Person company = app.people().stream().filter(Person::company).findFirst().orElseThrow();
        YearMonth month = YearMonth.of(2026, 9);
        insert(app, "company", company, month, 50000, ReviewStatus.READY);
        Path output = temp.resolve("company-only.xls");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new BankExcelExportService(app).exportMonth(month, output));
        assertTrue(error.getMessage().contains("个人报销金额"));
        assertFalse(Files.exists(output));
    }

    private static void insert(AppService app, String id, Person owner, YearMonth month, long cents,
                               ReviewStatus status) throws Exception {
        Invoice invoice = new Invoice();
        invoice.id = id;
        invoice.originalPath = "originals/" + id + ".pdf";
        invoice.originalName = id + ".pdf";
        invoice.sha256 = UUID.randomUUID().toString();
        invoice.importedAt = "2026-09-29T00:00:00Z";
        invoice.ownerId = owner.id();
        invoice.reimbursementMonth = month;
        invoice.amountCents = cents;
        invoice.category = Category.TRANSPORT;
        invoice.reviewStatus = status;
        app.database().insertInvoice(invoice);
    }
}
