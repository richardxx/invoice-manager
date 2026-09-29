package com.localinvoice;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public final class ZipInvoiceImportService {
    private static final long MAX_ARCHIVE_BYTES = 500L * 1024 * 1024;
    private static final long MAX_NESTED_ZIP_BYTES = 100L * 1024 * 1024;
    private static final long MAX_PDF_BYTES = 25L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 500L * 1024 * 1024;
    private static final int MAX_ENTRIES = 2_000;
    private static final int MAX_PDFS = 500;
    private static final int MAX_DEPTH = 3;

    private final AppService app;
    private final Predicate<Invoice> invoiceDetector;

    public ZipInvoiceImportService(AppService app) {
        this(app, ZipInvoiceImportService::likelyInvoice);
    }

    ZipInvoiceImportService(AppService app, Predicate<Invoice> invoiceDetector) {
        this.app = app;
        this.invoiceDetector = invoiceDetector;
    }

    public Report importArchives(List<Path> archives, Consumer<String> progress) throws Exception {
        Path scratch = Files.createTempDirectory(app.root().resolve("staging"), "zip-import-");
        Report report = new Report(scratch);
        ScanLimit limit = new ScanLimit();
        try {
            for (Path archive : archives) {
                try {
                    if (!Files.isRegularFile(archive)) throw new IOException("ZIP 文件不存在：" + archive);
                    if (Files.size(archive) > MAX_ARCHIVE_BYTES) throw new IOException("ZIP 文件超过 500 MB：" + archive);
                    progress.accept("扫描压缩包：" + archive.getFileName());
                    scanZip(archive, archive.getFileName().toString(), 0, report, limit, progress);
                } catch (IOException | IllegalArgumentException error) {
                    report.warnings.add(archive.getFileName() + "：" + error.getMessage());
                    if (limit.entries >= MAX_ENTRIES || limit.pdfs >= MAX_PDFS
                            || limit.extractedBytes >= MAX_EXTRACTED_BYTES) break;
                }
            }
            return report;
        } catch (Exception failure) {
            report.close();
            throw failure;
        }
    }

    public ImportResult importSelected(Entry entry) {
        if (!entry.canImport()) return new ImportResult(entry.originalName, null, "此文件无法补充导入", false);
        ImportResult result = app.importPdf(entry.extracted, entry.originalName, null);
        updateFromImport(entry, result);
        return result;
    }

    private void scanZip(Path zipPath, String prefix, int depth, Report report, ScanLimit limit,
                         Consumer<String> progress) throws Exception {
        try (ZipFile zip = openZip(zipPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                if (++limit.entries > MAX_ENTRIES) throw new IOException("压缩包文件数超过 " + MAX_ENTRIES);
                if (zipEntry.isDirectory()) continue;
                String name = zipEntry.getName().replace('\\', '/');
                String display = prefix + " / " + name;
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".pdf")) {
                    if (++limit.pdfs > MAX_PDFS) throw new IOException("PDF 文件数超过 " + MAX_PDFS);
                    processPdf(zip, zipEntry, display, fileName(name), report, limit, progress);
                } else if (lower.endsWith(".zip")) {
                    if (depth >= MAX_DEPTH) {
                        report.warnings.add("已跳过嵌套层数过深的压缩包：" + display);
                        continue;
                    }
                    Path nested = Files.createTempFile(report.scratch, "nested-", ".zip");
                    if (!copyLimited(zip, zipEntry, nested, MAX_NESTED_ZIP_BYTES, limit)) {
                        Files.deleteIfExists(nested);
                        report.warnings.add("已跳过超过 100 MB 的嵌套压缩包：" + display);
                        continue;
                    }
                    try {
                        scanZip(nested, display, depth + 1, report, limit, progress);
                    } catch (ZipException | IllegalArgumentException invalid) {
                        report.warnings.add("无法读取嵌套压缩包：" + display + " · " + invalid.getMessage());
                    }
                }
            }
        }
    }

    private void processPdf(ZipFile zip, ZipEntry zipEntry, String display, String originalName,
                            Report report, ScanLimit limit, Consumer<String> progress) throws Exception {
        progress.accept("检测发票：" + display);
        Entry entry = new Entry(display, originalName);
        report.entries.add(entry);
        if (zipEntry.getSize() > MAX_PDF_BYTES) {
            entry.status = Status.TOO_LARGE;
            entry.message = "PDF 超过 25 MB";
            return;
        }
        Path extracted = Files.createTempFile(report.scratch, "invoice-", ".pdf");
        try {
            if (!copyLimited(zip, zipEntry, extracted, MAX_PDF_BYTES, limit)) {
                Files.deleteIfExists(extracted);
                entry.status = Status.TOO_LARGE;
                entry.message = "PDF 超过 25 MB";
                return;
            }
        } catch (IOException failure) {
            entry.message = failure.getMessage();
            throw failure;
        }
        entry.extracted = extracted;
        try {
            Invoice existing = app.database().findHash(AppService.sha256(extracted));
            if (existing != null && !"DELETED".equals(existing.intakeStatus)) {
                entry.status = Status.EXISTING;
                entry.message = "相同 PDF 已在发票池";
                entry.invoiceId = existing.id;
                return;
            }
            Invoice recognized = app.inspectLocalPdf(extracted);
            entry.recognized = recognized;
            if (!invoiceDetector.test(recognized)) {
                entry.status = Status.NOT_DETECTED;
                entry.message = "未识别为发票，可选择后手动导入";
                return;
            }
            ImportResult imported = app.importPdf(extracted, originalName, recognized);
            updateFromImport(entry, imported);
        } catch (Exception error) {
            entry.status = Status.FAILED;
            entry.message = error.getMessage() == null ? error.toString() : error.getMessage();
        }
    }

    static boolean likelyInvoice(Invoice invoice) {
        if (invoice.amountCents == null || invoice.amountCents <= 0) return false;
        return invoice.invoiceNumber != null && !invoice.invoiceNumber.isBlank()
                || invoice.issuer != null && !invoice.issuer.isBlank() && invoice.issueDate != null
                || invoice.category == Category.TRANSPORT && invoice.traveler != null
                && invoice.travelDate != null;
    }

    private static void updateFromImport(Entry entry, ImportResult result) {
        entry.message = result.message();
        if (result.success()) {
            entry.status = Status.IMPORTED;
            entry.invoiceId = result.invoice().id;
            entry.recognized = result.invoice();
        } else if (result.message().contains("相同的文件已入池")) {
            entry.status = Status.EXISTING;
            entry.invoiceId = result.invoice() == null ? null : result.invoice().id;
            entry.recognized = result.invoice();
        } else {
            entry.status = Status.FAILED;
        }
    }

    private static boolean copyLimited(ZipFile zip, ZipEntry entry, Path target, long maxBytes,
                                       ScanLimit limit) throws IOException {
        if (entry.getSize() > maxBytes) return false;
        long written = 0;
        try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                written += count;
                limit.extractedBytes += count;
                if (limit.extractedBytes > MAX_EXTRACTED_BYTES)
                    throw new IOException("压缩包解压数据超过 500 MB，已停止导入");
                if (written > maxBytes) return false;
                output.write(buffer, 0, count);
            }
        }
        return true;
    }

    private static ZipFile openZip(Path path) throws IOException {
        try {
            return new ZipFile(path.toFile(), StandardCharsets.UTF_8);
        } catch (ZipException | IllegalArgumentException invalidUtf8) {
            return new ZipFile(path.toFile(), Charset.forName("GBK"));
        }
    }

    private static String fileName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isBlank()) return "invoice.pdf";
        return name.length() <= 200 ? name : name.substring(name.length() - 200);
    }

    private static final class ScanLimit {
        int entries;
        int pdfs;
        long extractedBytes;
    }

    public enum Status {
        IMPORTED("已入池"), NOT_DETECTED("待确认"), EXISTING("已存在"),
        FAILED("处理失败"), TOO_LARGE("文件过大");

        private final String label;
        Status(String label) { this.label = label; }
        public String label() { return label; }
    }

    public static final class Entry {
        private final String displayPath;
        private final String originalName;
        private Path extracted;
        private Invoice recognized;
        private Status status = Status.FAILED;
        private String message = "处理未完成";
        private String invoiceId;

        private Entry(String displayPath, String originalName) {
            this.displayPath = displayPath;
            this.originalName = originalName;
        }

        public String displayPath() { return displayPath; }
        public String originalName() { return originalName; }
        public Path extracted() { return extracted; }
        public Invoice recognized() { return recognized; }
        public Status status() { return status; }
        public String message() { return message; }
        public String invoiceId() { return invoiceId; }
        public boolean canImport() { return extracted != null && status != Status.IMPORTED && status != Status.EXISTING; }
    }

    public static final class Report implements AutoCloseable {
        private final Path scratch;
        private final List<Entry> entries = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        private Report(Path scratch) { this.scratch = scratch.toAbsolutePath().normalize(); }
        public List<Entry> entries() { return List.copyOf(entries); }
        public List<String> warnings() { return List.copyOf(warnings); }
        public long importedCount() { return entries.stream().filter(entry -> entry.status == Status.IMPORTED).count(); }

        @Override public void close() throws IOException {
            if (!Files.exists(scratch)) return;
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(scratch))
                        throw new IOException("ZIP 临时文件路径无效");
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
