package com.localinvoice;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseMigrationTest {
    @TempDir Path temp;

    @Test void upgradesVersionTwoAndPreservesExistingPeople() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("invoice-manager.db"));
             var statement = db.createStatement()) {
            statement.execute("CREATE TABLE person (id TEXT PRIMARY KEY, name TEXT UNIQUE NOT NULL, "
                    + "avatar_path TEXT, is_company INTEGER NOT NULL, created_at TEXT NOT NULL)");
            statement.execute("INSERT INTO person VALUES('old','旧人员','preset:sky',0,'2026-09-01T00:00:00Z')");
            statement.execute("PRAGMA user_version=2");
        }
        Database database = new Database(temp);
        assertTrue(Files.isRegularFile(temp.resolve("invoice-manager.db.v2-backup")));
        Person old = database.person("old");
        assertEquals("旧人员", old.name());
        assertEquals("preset:sky", old.avatarPath());
        assertFalse(old.payment().missingFields().isEmpty());
        PaymentDetails payment = new PaymentDetails("00123", "旧人员", PaymentDetails.BankType.BOC,
                "012345678901", "身份证", "1234567890");
        database.updatePaymentDetails(old.id(), payment);
        assertEquals(payment, new Database(temp).person("old").payment());
    }

    @Test void upgradesVersionOneWithoutChangingInvoiceData() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("invoice-manager.db"));
             var statement = db.createStatement()) {
            statement.execute("CREATE TABLE invoice (id TEXT PRIMARY KEY, original_path TEXT NOT NULL, "
                    + "original_name TEXT NOT NULL, sha256 TEXT UNIQUE NOT NULL, imported_at TEXT NOT NULL, "
                    + "issuer TEXT, buyer TEXT, traveler TEXT, travel_date TEXT, invoice_number TEXT, "
                    + "issue_date TEXT, amount_cents INTEGER, category TEXT, reimbursement_month TEXT, "
                    + "owner_id TEXT, review_status TEXT NOT NULL, duplicate_of TEXT, "
                    + "recognition_source TEXT, manually_edited TEXT)");
            statement.execute("INSERT INTO invoice(id,original_path,original_name,sha256,imported_at,"
                    + "issuer,amount_cents,category,reimbursement_month,review_status) VALUES "
                    + "('legacy','originals/legacy.pdf','legacy.pdf','legacy-hash','2026-09-01T00:00:00Z',"
                    + "'原开票方',46800,'DINING','2026-09','READY')");
            statement.execute("PRAGMA user_version=1");
        }

        Database migrated = new Database(temp);
        assertTrue(Files.isRegularFile(temp.resolve("invoice-manager.db.v1-backup")));
        Invoice preserved = migrated.invoice("legacy");
        assertEquals("原开票方", preserved.issuer);
        assertEquals(46800L, preserved.amountCents);
        assertEquals("IN_POOL", preserved.intakeStatus);
        assertNull(preserved.reimbursedAt);
        assertFalse(migrated.monthCompleted(YearMonth.of(2026, 9)));
        assertEquals("原开票方", new Database(temp).invoice("legacy").issuer);
    }
}
