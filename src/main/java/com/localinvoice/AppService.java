package com.localinvoice;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

public final class AppService {
    private final Path root;
    private final Database database;
    private final RecognitionService recognition;
    private final OpenAiVisionRecognition aiRecognition = new OpenAiVisionRecognition();
    private final SettingsService settings;

    public AppService(Path root, Path projectRoot) throws Exception {
        this.root = root.toAbsolutePath();
        Files.createDirectories(this.root.resolve("originals"));
        Files.createDirectories(this.root.resolve("avatars"));
        Files.createDirectories(this.root.resolve("staging"));
        this.database = new Database(this.root);
        this.recognition = new RecognitionService(projectRoot);
        this.settings = new SettingsService(this.root);
    }

    public Path root() { return root; }
    public Database database() { return database; }
    public SettingsService settings() { return settings; }
    public boolean ocrAvailable() { return recognition.ocrAvailable(); }
    public List<Person> people() throws SQLException { return database.people(); }
    public List<Person> recentOwners() throws SQLException { return database.recentOwners(); }
    public List<Invoice> invoices() throws SQLException { return database.invoices(); }
    public Invoice invoice(String id) throws SQLException { return database.invoice(id); }
    public Map<String, Long> completedTotals() throws SQLException { return database.completedTotals(); }

    public Person addPerson(String name, String avatar) throws Exception {
        String clean = validName(name);
        ensureUniqueName(clean, null);
        return database.addPerson(clean, AvatarPresets.valid(avatar));
    }

    public Person addPerson(String name, String avatar, PaymentDetails payment) throws Exception {
        String clean = validName(name);
        ensureUniqueName(clean, null);
        return database.addPerson(clean, AvatarPresets.valid(avatar), payment.requireComplete());
    }

    public void updatePerson(Person person, String name, String avatar) throws Exception {
        if (person.company() || Database.COMPANY_ID.equals(person.id()))
            throw new IllegalArgumentException("公司是固定账户");
        String clean = validName(name);
        ensureUniqueName(clean, person.id());
        String avatarPath = avatar == null ? person.avatarPath() : AvatarPresets.valid(avatar);
        database.updatePerson(new Person(person.id(), clean, avatarPath, false, person.payment()));
    }

    public void updatePerson(Person person, String name, String avatar, PaymentDetails payment) throws Exception {
        if (person.company() || Database.COMPANY_ID.equals(person.id()))
            throw new IllegalArgumentException("公司名称和头像不能修改");
        String clean = validName(name);
        ensureUniqueName(clean, person.id());
        String avatarPath = avatar == null ? person.avatarPath() : AvatarPresets.valid(avatar);
        database.updatePerson(new Person(person.id(), clean, avatarPath, false, payment.requireComplete()));
    }

    public void updatePaymentDetails(Person person, PaymentDetails payment) throws SQLException {
        database.updatePaymentDetails(person.id(), payment.requireComplete());
    }

    public void deletePerson(String id) throws Exception {
        for (Invoice invoice : invoices()) {
            if (id.equals(invoice.ownerId)) throw new IllegalArgumentException("请先取消或转移此人的发票归属");
        }
        database.deletePerson(id);
    }

    public ImportResult importPdf(Path source) {
        return importPdf(source, source.getFileName().toString(), null);
    }

    ImportResult importPdf(Path source, String originalName, Invoice recognized) {
        Path staged = null;
        Path managed = null;
        String filename = originalName;
        try {
            if (!filename.toLowerCase().endsWith(".pdf")) throw new IllegalArgumentException("仅支持PDF文件");
            long size = Files.size(source);
            if (size < 8 || size > 25L * 1024 * 1024) throw new IllegalArgumentException("PDF大小须在8字节至25MB之间");
            try (InputStream in = Files.newInputStream(source)) {
                byte[] signature = in.readNBytes(5);
                if (!new String(signature, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
                    throw new IllegalArgumentException("文件内容不是PDF");
            }
            staged = Files.createTempFile(root.resolve("staging"), "import-", ".pdf");
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            String hash = sha256(staged);
            Invoice existing = database.findHash(hash);
            if (existing != null) {
                if ("DELETED".equals(existing.intakeStatus)) {
                    if (!Files.isRegularFile(original(existing)))
                        Files.copy(staged, original(existing));
                    database.restoreInvoice(existing.id);
                    return new ImportResult(filename, database.invoice(existing.id), "已重新入池", true);
                }
                return new ImportResult(filename, existing, "完全相同的文件已入池", false);
            }

            Invoice invoice = recognized == null ? recognition.recognize(staged) : recognized;
            invoice.reimbursementMonth = null;
            invoice.id = UUID.randomUUID().toString();
            invoice.originalPath = "originals/" + invoice.id + ".pdf";
            invoice.originalName = filename;
            invoice.sha256 = hash;
            invoice.importedAt = Instant.now().toString();
            Invoice possible = database.findSemanticDuplicate(invoice);
            if (possible != null) {
                invoice.duplicateOf = possible.id;
                invoice.reviewStatus = ReviewStatus.POSSIBLE_DUPLICATE;
            }
            managed = root.resolve(invoice.originalPath);
            Files.move(staged, managed);
            staged = null;
            try {
                database.insertInvoice(invoice);
            } catch (Exception failure) {
                Files.deleteIfExists(managed);
                throw failure;
            }
            String message = invoice.reviewStatus == ReviewStatus.READY ? "导入成功" : "已入池，需核对识别信息";
            return new ImportResult(filename, invoice, message, true);
        } catch (Exception error) {
            return new ImportResult(filename, null, error.getMessage() == null ? error.toString() : error.getMessage(), false);
        } finally {
            if (staged != null) try { Files.deleteIfExists(staged); } catch (Exception ignored) { }
        }
    }

    Invoice inspectLocalPdf(Path pdf) throws Exception {
        return recognition.recognize(pdf);
    }

    public void saveInvoice(Invoice invoice, boolean confirmReview) throws Exception {
        ensureNotReimbursed(requiredInvoice(invoice.id));
        if (invoice.amountCents != null && invoice.amountCents <= 0)
            throw new IllegalArgumentException("金额必须大于0");
        if (confirmReview) {
            if (invoice.issuer == null || invoice.issuer.isBlank() || invoice.issueDate == null
                    || invoice.amountCents == null || invoice.category == null)
                throw new IllegalArgumentException("请填写开票方、开票日期、金额和类型后再确认");
            invoice.reviewStatus = ReviewStatus.READY;
            if (invoice.duplicateOf != null && (invoice.manuallyEdited == null || invoice.manuallyEdited.isBlank()))
                throw new IllegalArgumentException("请填写疑似重复发票的核对说明");
        } else if (invoice.reviewStatus == ReviewStatus.READY
                && (invoice.issuer == null || invoice.issuer.isBlank() || invoice.issueDate == null
                || invoice.amountCents == null || invoice.category == null)) {
            invoice.reviewStatus = ReviewStatus.NEEDS_REVIEW;
        }
        database.updateInvoice(invoice);
    }

    public void assign(String invoiceId, String ownerId) throws Exception {
        if (database.person(ownerId) == null) throw new IllegalArgumentException("人员不存在");
        Invoice invoice = requiredInvoice(invoiceId);
        ensureNotReimbursed(invoice);
        if (invoice.reimbursementMonth != null && monthCompleted(invoice.reimbursementMonth))
            throw new IllegalStateException("报销月份已完成，请先撤销报销");
        invoice.ownerId = ownerId;
        database.updateInvoice(invoice);
        database.rememberOwner(ownerId);
    }

    public void assign(String invoiceId, String ownerId, YearMonth month) throws Exception {
        assignMany(List.of(invoiceId), ownerId, month);
    }

    public void assignMany(List<String> invoiceIds, String ownerId, YearMonth month) throws Exception {
        if (month == null) throw new IllegalArgumentException("请选择报销月份");
        if (database.person(ownerId) == null) throw new IllegalArgumentException("人员不存在");
        if (invoiceIds == null || invoiceIds.isEmpty() || invoiceIds.stream().anyMatch(id -> id == null || id.isBlank())
                || invoiceIds.size() != new java.util.HashSet<>(invoiceIds).size())
            throw new IllegalArgumentException("请选择不重复的发票");
        if (monthCompleted(month)) throw new IllegalStateException("报销月份已完成，请先撤销报销");
        for (String id : invoiceIds) ensureNotReimbursed(requiredInvoice(id));
        database.assignInvoices(invoiceIds, ownerId, month);
    }

    public Invoice reRecognize(String invoiceId) throws Exception {
        return reRecognize(invoiceId, ignored -> { });
    }

    public Invoice reRecognize(String invoiceId, Consumer<String> progress) throws Exception {
        AiSettings options = settings.current();
        if (!options.configured()) throw new IllegalStateException("请先在选项中配置 AI 服务");
        Invoice existing = requiredInvoice(invoiceId);
        ensureNotReimbursed(existing);
        Invoice recognized = aiRecognition.recognize(original(existing), options, progress);
        mergeRecognition(existing, recognized, "AI");
        database.updateInvoice(existing);
        return existing;
    }

    public Invoice reRecognizeOcr(String invoiceId) throws Exception {
        Invoice existing = requiredInvoice(invoiceId);
        ensureNotReimbursed(existing);
        Invoice recognized = recognition.recognize(original(existing), true);
        mergeRecognition(existing, recognized, recognized.recognitionSource);
        database.updateInvoice(existing);
        return existing;
    }

    private static void mergeRecognition(Invoice existing, Invoice recognized, String source) {
        if (recognized.issuer == null && recognized.amountCents == null && recognized.issueDate == null)
            throw new IllegalStateException("未识别出有效字段，原记录已保留");
        if (recognized.issuer != null) existing.issuer = recognized.issuer;
        if (recognized.buyer != null) existing.buyer = recognized.buyer;
        if (recognized.traveler != null) existing.traveler = recognized.traveler;
        if (recognized.travelDate != null) existing.travelDate = recognized.travelDate;
        if (recognized.invoiceNumber != null) existing.invoiceNumber = recognized.invoiceNumber;
        if (recognized.issueDate != null) existing.issueDate = recognized.issueDate;
        if (recognized.amountCents != null) existing.amountCents = recognized.amountCents;
        if (recognized.category != Category.OTHER) existing.category = recognized.category;
        existing.recognitionSource = source;
        existing.reviewStatus = ReviewStatus.NEEDS_REVIEW;
    }

    public void unassign(String invoiceId) throws Exception {
        Invoice invoice = requiredInvoice(invoiceId);
        ensureNotReimbursed(invoice);
        invoice.ownerId = null;
        database.updateInvoice(invoice);
    }

    public void deleteInvoice(String invoiceId) throws Exception {
        Invoice invoice = requiredInvoice(invoiceId);
        ensureNotReimbursed(invoice);
        database.removeFromPool(invoiceId);
    }

    public boolean monthCompleted(YearMonth month) throws SQLException { return database.monthCompleted(month); }

    public int setMonthCompleted(YearMonth month, boolean complete) throws SQLException {
        if (database.monthCompleted(month) == complete)
            throw new IllegalStateException(complete ? "本月已完成报销" : "本月尚未完成报销");
        return database.setMonthCompleted(month, complete);
    }

    private static void ensureNotReimbursed(Invoice invoice) {
        if (invoice.reimbursedAt != null) throw new IllegalStateException("已报销发票不可修改，请先撤销该月报销");
    }

    public List<Invoice> countedInMonth(YearMonth month) throws SQLException {
        List<Invoice> result = new ArrayList<>();
        for (Invoice invoice : invoices()) {
            if (invoice.counted() && month.equals(invoice.reimbursementMonth)) result.add(invoice);
        }
        return result;
    }

    public List<Invoice> assignedInMonth(YearMonth month) throws SQLException {
        List<Invoice> result = new ArrayList<>();
        for (Invoice invoice : invoices()) {
            if (invoice.ownerId != null && month.equals(invoice.reimbursementMonth)) result.add(invoice);
        }
        return result;
    }

    public Map<String, long[]> totals(YearMonth month) throws SQLException {
        return categoryTotals(countedInMonth(month));
    }

    public Map<String, long[]> displayTotals(YearMonth month) throws SQLException {
        return categoryTotals(assignedInMonth(month));
    }

    private Map<String, long[]> categoryTotals(List<Invoice> invoices) throws SQLException {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (Person person : people()) totals.put(person.id(), new long[Category.values().length]);
        for (Invoice invoice : invoices) {
            if (invoice.amountCents == null || invoice.amountCents <= 0 || invoice.category == null) continue;
            long[] row = totals.get(invoice.ownerId);
            if (row != null) row[invoice.category.ordinal()] = Math.addExact(row[invoice.category.ordinal()], invoice.amountCents);
        }
        return totals;
    }

    public List<Person> activePeople(YearMonth month) throws SQLException {
        java.util.Set<String> ownerIds = new java.util.HashSet<>();
        for (Invoice invoice : assignedInMonth(month)) ownerIds.add(invoice.ownerId);
        List<Person> active = new ArrayList<>();
        for (Person person : people()) if (ownerIds.contains(person.id())) active.add(person);
        return active;
    }

    public Path original(Invoice invoice) {
        Path path = root.resolve(invoice.originalPath).normalize();
        if (!path.startsWith(root.resolve("originals"))) throw new IllegalArgumentException("无效的原始文件路径");
        return path;
    }

    private Invoice requiredInvoice(String id) throws SQLException {
        Invoice invoice = database.invoice(id);
        if (invoice == null) throw new IllegalArgumentException("发票不存在");
        return invoice;
    }

    private void ensureUniqueName(String name, String excludeId) throws SQLException {
        for (Person person : database.people()) {
            if (person.name().equals(name) && !person.id().equals(excludeId))
                throw new IllegalArgumentException("姓名已存在，请使用不同的姓名");
        }
    }

    private static String validName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("姓名不能为空");
        return name.trim();
    }

    public static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
