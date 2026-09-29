package com.localinvoice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

public final class Database {
    public static final String COMPANY_ID = "company";
    private final Path path;

    public Database(Path root) throws Exception {
        Files.createDirectories(root);
        this.path = root.resolve("invoice-manager.db");
        try (Connection db = connect(); Statement st = db.createStatement()) {
            int schemaVersion;
            try (ResultSet version = st.executeQuery("PRAGMA user_version")) {
                schemaVersion = version.next() ? version.getInt(1) : 0;
            }
            if (schemaVersion > 3) throw new IllegalStateException("数据库版本高于当前程序支持的版本");
            if (schemaVersion == 1) {
                Path backup = root.resolve("invoice-manager.db.v1-backup");
                if (!Files.exists(backup)) Files.copy(path, backup, StandardCopyOption.COPY_ATTRIBUTES);
            }
            st.execute("CREATE TABLE IF NOT EXISTS person (id TEXT PRIMARY KEY, name TEXT UNIQUE NOT NULL, "
                    + "avatar_path TEXT, is_company INTEGER NOT NULL, created_at TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS invoice (id TEXT PRIMARY KEY, original_path TEXT NOT NULL, "
                    + "original_name TEXT NOT NULL, sha256 TEXT UNIQUE NOT NULL, imported_at TEXT NOT NULL, "
                    + "issuer TEXT, buyer TEXT, traveler TEXT, travel_date TEXT, invoice_number TEXT, "
                    + "issue_date TEXT, amount_cents INTEGER, category TEXT, reimbursement_month TEXT, "
                    + "owner_id TEXT REFERENCES person(id), review_status TEXT NOT NULL, duplicate_of TEXT, "
                    + "recognition_source TEXT, manually_edited TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS recent_owner (owner_id TEXT PRIMARY KEY REFERENCES person(id), "
                    + "selected_at TEXT NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS invoice_month_idx ON invoice(reimbursement_month, owner_id, category)");
            st.execute("CREATE INDEX IF NOT EXISTS invoice_number_idx ON invoice(invoice_number, issue_date, amount_cents)");
            st.execute("INSERT OR IGNORE INTO person(id,name,avatar_path,is_company,created_at) VALUES "
                    + "('company','公司',NULL,1,'2026-01-01T00:00:00Z')");
            if (schemaVersion < 2) {
                if (!hasColumn(db, "invoice", "intake_status"))
                    st.execute("ALTER TABLE invoice ADD COLUMN intake_status TEXT NOT NULL DEFAULT 'IN_POOL'");
                if (!hasColumn(db, "invoice", "reimbursed_at"))
                    st.execute("ALTER TABLE invoice ADD COLUMN reimbursed_at TEXT");
                st.execute("CREATE TABLE IF NOT EXISTS reimbursement_month "
                        + "(month TEXT PRIMARY KEY, completed_at TEXT, updated_at TEXT NOT NULL)");
                st.execute("PRAGMA user_version=2");
            }
            if (schemaVersion < 3) {
                if (schemaVersion == 2) {
                    Path backup = root.resolve("invoice-manager.db.v2-backup");
                    if (!Files.exists(backup)) Files.copy(path, backup, StandardCopyOption.COPY_ATTRIBUTES);
                }
                for (String column : List.of("account_number", "account_name", "bank_type", "cnaps_number",
                        "identity_type", "identity_number")) {
                    if (!hasColumn(db, "person", column)) st.execute("ALTER TABLE person ADD COLUMN " + column + " TEXT");
                }
                st.execute("PRAGMA user_version=3");
            }
        }
    }

    private Connection connect() throws SQLException {
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        }
        return db;
    }

    private static boolean hasColumn(Connection db, String table, String column) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) if (column.equals(rs.getString("name"))) return true;
            return false;
        }
    }

    public Path path() { return path; }

    public List<Person> people() throws SQLException {
        List<Person> result = new ArrayList<>();
        try (Connection db = connect(); Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM person ORDER BY is_company,name")) {
            while (rs.next()) result.add(person(rs));
        }
        return result;
    }

    public Person person(String id) throws SQLException {
        try (Connection db = connect(); PreparedStatement ps = db.prepareStatement(
                "SELECT * FROM person WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? person(rs) : null; }
        }
    }

    public Person addPerson(String name, String avatarPath) throws SQLException {
        return addPerson(name, avatarPath, PaymentDetails.empty());
    }

    public Person addPerson(String name, String avatarPath, PaymentDetails payment) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (Connection db = connect(); PreparedStatement ps = db.prepareStatement(
                "INSERT INTO person(id,name,avatar_path,is_company,created_at,account_number,account_name,"
                        + "bank_type,cnaps_number,identity_type,identity_number) VALUES(?,?,?,0,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, name.trim());
            ps.setString(3, avatarPath);
            ps.setString(4, Instant.now().toString());
            bindPayment(ps, 5, payment);
            ps.executeUpdate();
        }
        return new Person(id, name.trim(), avatarPath, false, payment);
    }

    public void updatePerson(Person person) throws SQLException {
        if (person.company() || COMPANY_ID.equals(person.id())) throw new IllegalArgumentException("公司不能修改");
        try (Connection db = connect(); PreparedStatement ps = db.prepareStatement(
                "UPDATE person SET name=?,avatar_path=?,account_number=?,account_name=?,bank_type=?,"
                        + "cnaps_number=?,identity_type=?,identity_number=? WHERE id=?")) {
            ps.setString(1, person.name().trim());
            ps.setString(2, person.avatarPath());
            bindPayment(ps, 3, person.payment());
            ps.setString(9, person.id());
            ps.executeUpdate();
        }
    }

    public void updatePaymentDetails(String personId, PaymentDetails payment) throws SQLException {
        try (Connection db = connect(); PreparedStatement ps = db.prepareStatement(
                "UPDATE person SET account_number=?,account_name=?,bank_type=?,cnaps_number=?,"
                        + "identity_type=?,identity_number=? WHERE id=?")) {
            bindPayment(ps, 1, payment);
            ps.setString(7, personId);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("人员不存在");
        }
    }

    private static void bindPayment(PreparedStatement ps, int first, PaymentDetails payment) throws SQLException {
        ps.setString(first, payment.accountNumber());
        ps.setString(first + 1, payment.accountName());
        ps.setString(first + 2, payment.bankType() == null ? null : payment.bankType().name());
        ps.setString(first + 3, payment.cnapsNumber());
        ps.setString(first + 4, payment.identityType());
        ps.setString(first + 5, payment.identityNumber());
    }

    public void deletePerson(String id) throws SQLException {
        if (COMPANY_ID.equals(id)) throw new IllegalArgumentException("公司不能删除");
        try (Connection db = connect(); PreparedStatement ps = db.prepareStatement("DELETE FROM person WHERE id=?")) {
            ps.setString(1, id);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("人员不存在");
        }
    }

    public Map<String, Long> completedTotals() throws SQLException {
        Map<String, Long> totals = new HashMap<>();
        try (Connection db = connect(); Statement st = db.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT owner_id,amount_cents FROM invoice WHERE intake_status='IN_POOL' "
                        + "AND reimbursed_at IS NOT NULL AND owner_id IS NOT NULL AND amount_cents>0")) {
            while (rs.next()) totals.merge(rs.getString(1), rs.getLong(2), Math::addExact);
        }
        return totals;
    }

    public List<Invoice> invoices() throws SQLException {
        List<Invoice> result = new ArrayList<>();
        try (Connection db = connect(); Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM invoice WHERE intake_status='IN_POOL' ORDER BY imported_at DESC, id")) {
            while (rs.next()) result.add(invoice(rs));
        }
        return result;
    }

    public Invoice invoice(String id) throws SQLException {
        try (Connection db = connect(); PreparedStatement ps = db.prepareStatement(
                "SELECT * FROM invoice WHERE id=? AND intake_status='IN_POOL'")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? invoice(rs) : null; }
        }
    }

    public Invoice findHash(String hash) throws SQLException {
        try (Connection db = connect(); PreparedStatement ps = db.prepareStatement("SELECT * FROM invoice WHERE sha256=?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? invoice(rs) : null; }
        }
    }

    public void restoreInvoice(String id) throws SQLException {
        try (Connection db = connect(); PreparedStatement ps = db.prepareStatement(
                "UPDATE invoice SET intake_status='IN_POOL' WHERE id=? AND intake_status='DELETED'")) {
            ps.setString(1, id);
            if (ps.executeUpdate() != 1) throw new IllegalArgumentException("发票无法恢复入池");
        }
    }

    public Invoice findSemanticDuplicate(Invoice candidate) throws SQLException {
        if (candidate.invoiceNumber == null || candidate.invoiceNumber.isBlank()
                || candidate.issueDate == null || candidate.amountCents == null) return null;
        try (Connection db = connect(); PreparedStatement ps = db.prepareStatement(
                "SELECT * FROM invoice WHERE intake_status='IN_POOL' AND invoice_number=? AND issue_date=? AND amount_cents=? LIMIT 1")) {
            ps.setString(1, candidate.invoiceNumber);
            ps.setString(2, candidate.issueDate.toString());
            ps.setLong(3, candidate.amountCents);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? invoice(rs) : null; }
        }
    }

    public void insertInvoice(Invoice invoice) throws SQLException {
        String sql = "INSERT INTO invoice(id,original_path,original_name,sha256,imported_at,issuer,buyer,traveler,"
                + "travel_date,invoice_number,issue_date,amount_cents,category,reimbursement_month,owner_id,"
                + "review_status,duplicate_of,recognition_source,manually_edited) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection db = connect(); PreparedStatement ps = db.prepareStatement(sql)) {
            bindInvoice(ps, invoice);
            ps.executeUpdate();
        }
    }

    public void updateInvoice(Invoice invoice) throws SQLException {
        String sql = "UPDATE invoice SET original_path=?,original_name=?,sha256=?,imported_at=?,issuer=?,buyer=?,"
                + "traveler=?,travel_date=?,invoice_number=?,issue_date=?,amount_cents=?,category=?,"
                + "reimbursement_month=?,owner_id=?,review_status=?,duplicate_of=?,recognition_source=?,"
                + "manually_edited=? WHERE id=? AND intake_status='IN_POOL'";
        try (Connection db = connect(); PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, invoice.originalPath);
            ps.setString(2, invoice.originalName);
            ps.setString(3, invoice.sha256);
            ps.setString(4, invoice.importedAt);
            ps.setString(5, invoice.issuer);
            ps.setString(6, invoice.buyer);
            ps.setString(7, invoice.traveler);
            ps.setString(8, date(invoice.travelDate));
            ps.setString(9, invoice.invoiceNumber);
            ps.setString(10, date(invoice.issueDate));
            ps.setObject(11, invoice.amountCents);
            ps.setString(12, invoice.category == null ? null : invoice.category.name());
            ps.setString(13, invoice.reimbursementMonth == null ? null : invoice.reimbursementMonth.toString());
            ps.setString(14, invoice.ownerId);
            ps.setString(15, invoice.reviewStatus.name());
            ps.setString(16, invoice.duplicateOf);
            ps.setString(17, invoice.recognitionSource);
            ps.setString(18, invoice.manuallyEdited);
            ps.setString(19, invoice.id);
            if (ps.executeUpdate() != 1) throw new IllegalArgumentException("发票不存在");
        }
    }

    public void assignInvoices(List<String> ids, String ownerId, YearMonth month) throws SQLException {
        try (Connection db = connect()) {
            db.setAutoCommit(false);
            try (PreparedStatement update = db.prepareStatement(
                    "UPDATE invoice SET owner_id=?,reimbursement_month=? WHERE id=? AND intake_status='IN_POOL' AND reimbursed_at IS NULL");
                 PreparedStatement removeRecent = db.prepareStatement("DELETE FROM recent_owner WHERE owner_id=?");
                 PreparedStatement addRecent = db.prepareStatement(
                         "INSERT INTO recent_owner(owner_id,selected_at) VALUES(?,?)");
                 Statement trim = db.createStatement()) {
                for (String id : ids) {
                    update.setString(1, ownerId);
                    update.setString(2, month.toString());
                    update.setString(3, id);
                    if (update.executeUpdate() != 1) throw new IllegalArgumentException("所选发票不存在");
                }
                removeRecent.setString(1, ownerId);
                removeRecent.executeUpdate();
                addRecent.setString(1, ownerId);
                addRecent.setString(2, Instant.now().toString());
                addRecent.executeUpdate();
                trim.executeUpdate("DELETE FROM recent_owner WHERE owner_id NOT IN "
                        + "(SELECT owner_id FROM recent_owner ORDER BY selected_at DESC LIMIT 4)");
                db.commit();
            } catch (Exception error) {
                db.rollback();
                throw error;
            }
        }
    }

    public void removeFromPool(String id) throws SQLException {
        try (Connection db = connect(); PreparedStatement ps = db.prepareStatement(
                "UPDATE invoice SET intake_status='DELETED',owner_id=NULL,reimbursement_month=NULL "
                        + "WHERE id=? AND intake_status='IN_POOL' AND reimbursed_at IS NULL")) {
            ps.setString(1, id);
            if (ps.executeUpdate() != 1) throw new IllegalArgumentException("已报销发票不可删除，或发票已不在池中");
        }
    }

    public boolean monthCompleted(YearMonth month) throws SQLException {
        try (Connection db = connect(); PreparedStatement ps = db.prepareStatement(
                "SELECT completed_at FROM reimbursement_month WHERE month=?")) {
            ps.setString(1, month.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getString(1) != null; }
        }
    }

    public int setMonthCompleted(YearMonth month, boolean complete) throws SQLException {
        String now = Instant.now().toString();
        try (Connection db = connect()) {
            db.setAutoCommit(false);
            try {
                int changed;
                try (PreparedStatement update = db.prepareStatement(
                        "UPDATE invoice SET reimbursed_at=? WHERE intake_status='IN_POOL' "
                                + "AND owner_id IS NOT NULL AND reimbursement_month=?")) {
                    update.setString(1, complete ? now : null);
                    update.setString(2, month.toString());
                    changed = update.executeUpdate();
                }
                if (complete && changed == 0) throw new IllegalArgumentException("本月没有已归属的发票");
                try (PreparedStatement state = db.prepareStatement(
                        "INSERT INTO reimbursement_month(month,completed_at,updated_at) VALUES(?,?,?) "
                                + "ON CONFLICT(month) DO UPDATE SET completed_at=excluded.completed_at,updated_at=excluded.updated_at")) {
                    state.setString(1, month.toString());
                    state.setString(2, complete ? now : null);
                    state.setString(3, now);
                    state.executeUpdate();
                }
                db.commit();
                return changed;
            } catch (Exception error) {
                db.rollback();
                throw error;
            }
        }
    }

    public List<Person> recentOwners() throws SQLException {
        List<Person> result = new ArrayList<>();
        try (Connection db = connect(); Statement st = db.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT p.* FROM recent_owner r JOIN person p ON p.id=r.owner_id "
                        + "ORDER BY r.selected_at DESC LIMIT 4")) {
            while (rs.next()) result.add(person(rs));
        }
        return result;
    }

    public void rememberOwner(String id) throws SQLException {
        try (Connection db = connect()) {
            db.setAutoCommit(false);
            try (PreparedStatement del = db.prepareStatement("DELETE FROM recent_owner WHERE owner_id=?");
                 PreparedStatement put = db.prepareStatement("INSERT INTO recent_owner(owner_id,selected_at) VALUES(?,?)");
                 Statement trim = db.createStatement()) {
                del.setString(1, id);
                del.executeUpdate();
                put.setString(1, id);
                put.setString(2, Instant.now().toString());
                put.executeUpdate();
                trim.executeUpdate("DELETE FROM recent_owner WHERE owner_id NOT IN "
                        + "(SELECT owner_id FROM recent_owner ORDER BY selected_at DESC LIMIT 4)");
                db.commit();
            } catch (Exception error) {
                db.rollback();
                throw error;
            }
        }
    }

    public void snapshot(Path destination) throws SQLException {
        try (Connection db = connect(); PreparedStatement ps = db.prepareStatement("VACUUM INTO ?")) {
            ps.setString(1, destination.toAbsolutePath().toString());
            ps.execute();
        }
    }

    private static Person person(ResultSet rs) throws SQLException {
        String bankType = rs.getString("bank_type");
        PaymentDetails payment = new PaymentDetails(rs.getString("account_number"), rs.getString("account_name"),
                bankType == null ? null : PaymentDetails.BankType.valueOf(bankType),
                rs.getString("cnaps_number"), rs.getString("identity_type"), rs.getString("identity_number"));
        return new Person(rs.getString("id"), rs.getString("name"), rs.getString("avatar_path"),
                rs.getInt("is_company") != 0, payment);
    }

    private static Invoice invoice(ResultSet rs) throws SQLException {
        Invoice i = new Invoice();
        i.id = rs.getString("id");
        i.originalPath = rs.getString("original_path");
        i.originalName = rs.getString("original_name");
        i.sha256 = rs.getString("sha256");
        i.importedAt = rs.getString("imported_at");
        i.issuer = rs.getString("issuer");
        i.buyer = rs.getString("buyer");
        i.traveler = rs.getString("traveler");
        i.travelDate = parseDate(rs.getString("travel_date"));
        i.invoiceNumber = rs.getString("invoice_number");
        i.issueDate = parseDate(rs.getString("issue_date"));
        long cents = rs.getLong("amount_cents");
        i.amountCents = rs.wasNull() ? null : cents;
        String category = rs.getString("category");
        i.category = category == null ? null : Category.valueOf(category);
        String month = rs.getString("reimbursement_month");
        i.reimbursementMonth = month == null ? null : YearMonth.parse(month);
        i.ownerId = rs.getString("owner_id");
        i.reviewStatus = ReviewStatus.valueOf(rs.getString("review_status"));
        i.duplicateOf = rs.getString("duplicate_of");
        i.recognitionSource = rs.getString("recognition_source");
        i.manuallyEdited = rs.getString("manually_edited");
        i.intakeStatus = rs.getString("intake_status");
        i.reimbursedAt = rs.getString("reimbursed_at");
        return i;
    }

    private static void bindInvoice(PreparedStatement ps, Invoice i) throws SQLException {
        ps.setString(1, i.id);
        ps.setString(2, i.originalPath);
        ps.setString(3, i.originalName);
        ps.setString(4, i.sha256);
        ps.setString(5, i.importedAt);
        ps.setString(6, i.issuer);
        ps.setString(7, i.buyer);
        ps.setString(8, i.traveler);
        ps.setString(9, date(i.travelDate));
        ps.setString(10, i.invoiceNumber);
        ps.setString(11, date(i.issueDate));
        ps.setObject(12, i.amountCents);
        ps.setString(13, i.category == null ? null : i.category.name());
        ps.setString(14, i.reimbursementMonth == null ? null : i.reimbursementMonth.toString());
        ps.setString(15, i.ownerId);
        ps.setString(16, i.reviewStatus.name());
        ps.setString(17, i.duplicateOf);
        ps.setString(18, i.recognitionSource);
        ps.setString(19, i.manuallyEdited);
    }

    private static String date(LocalDate d) { return d == null ? null : d.toString(); }
    private static LocalDate parseDate(String d) { return d == null ? null : LocalDate.parse(d); }
}
