package com.localinvoice;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class BackupService {
    private final AppService app;

    public BackupService(AppService app) { this.app = app; }

    public int backup(Path destination) throws Exception {
        Path target = destination.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path snapshot = Files.createTempFile(app.root().resolve("staging"), "snapshot-", ".db");
        Files.delete(snapshot);
        Path temporary = Files.createTempFile(target.getParent(), ".invoice-backup-", ".zip");
        try {
            app.database().snapshot(snapshot);
            List<Path> files = new ArrayList<>();
            files.add(snapshot);
            for (String folder : List.of("originals", "avatars")) {
                try (var walk = Files.walk(app.root().resolve(folder))) {
                    walk.filter(Files::isRegularFile).sorted().forEach(files::add);
                }
            }
            Properties manifest = new Properties();
            manifest.setProperty("format", "local-invoice-backup-v1");
            manifest.setProperty("count", Integer.toString(files.size()));
            try (OutputStream out = Files.newOutputStream(temporary); ZipOutputStream zip = new ZipOutputStream(out)) {
                for (int index = 0; index < files.size(); index++) {
                    Path file = files.get(index);
                    String relative = file.equals(snapshot) ? "invoice-manager.db"
                            : app.root().relativize(file).toString().replace('\\', '/');
                    manifest.setProperty("file." + index + ".path", relative);
                    manifest.setProperty("file." + index + ".sha256", AppService.sha256(file));
                    zip.putNextEntry(new ZipEntry(relative));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
                zip.putNextEntry(new ZipEntry("manifest.properties"));
                manifest.store(zip, "Local Invoice Manager backup");
                zip.closeEntry();
            }
            verify(temporary, null);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            return files.size() - 1;
        } finally {
            Files.deleteIfExists(snapshot);
            Files.deleteIfExists(temporary);
        }
    }

    public void restore(Path archive) throws Exception {
        Path root = app.root();
        Path staging = root.resolveSibling(root.getFileName() + "-restore-" + UUID.randomUUID());
        Path rollback = root.resolveSibling(root.getFileName() + "-previous-" + UUID.randomUUID());
        Files.createDirectories(staging);
        boolean movedCurrent = false;
        try {
            verify(archive, staging);
            validateDatabase(staging);
            Files.move(root, rollback);
            movedCurrent = true;
            try {
                Files.move(staging, root);
                Files.createDirectories(root.resolve("staging"));
                Files.createDirectories(root.resolve("originals"));
                Files.createDirectories(root.resolve("avatars"));
            } catch (Exception failure) {
                if (Files.exists(root)) deleteTree(root);
                Files.move(rollback, root);
                movedCurrent = false;
                throw failure;
            }
            movedCurrent = false;
            deleteTree(rollback);
        } finally {
            if (Files.exists(staging)) deleteTree(staging);
            if (movedCurrent && Files.exists(rollback) && !Files.exists(root)) Files.move(rollback, root);
        }
    }

    private static void verify(Path archive, Path destination) throws Exception {
        Path temporary = destination == null ? Files.createTempDirectory("invoice-backup-check-") : destination;
        try {
            Set<String> entries = new HashSet<>();
            long totalBytes = 0;
            try (InputStream in = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!entries.add(name) || entries.size() > 100_001)
                        throw new IllegalArgumentException("备份包含重复或过多文件");
                    if (!(name.equals("invoice-manager.db") || name.equals("manifest.properties")
                            || name.startsWith("originals/") || name.startsWith("avatars/")))
                        throw new IllegalArgumentException("备份包含意外文件");
                    Path output = temporary.resolve(entry.getName()).normalize();
                    if (!output.startsWith(temporary) || entry.getName().startsWith("/") || entry.getName().contains("\\"))
                        throw new IllegalArgumentException("备份包含不安全路径");
                    if (!entry.isDirectory()) {
                        Files.createDirectories(output.getParent());
                        try (OutputStream file = Files.newOutputStream(output)) {
                            byte[] buffer = new byte[64 * 1024];
                            int count;
                            while ((count = zip.read(buffer)) >= 0) {
                                totalBytes += count;
                                if (totalBytes > 20L * 1024 * 1024 * 1024)
                                    throw new IllegalArgumentException("备份解压大小超过20GB");
                                file.write(buffer, 0, count);
                            }
                        }
                    }
                    zip.closeEntry();
                }
            }
            Path manifestFile = temporary.resolve("manifest.properties");
            if (!Files.isRegularFile(manifestFile)) throw new IllegalArgumentException("备份清单缺失");
            Properties manifest = new Properties();
            try (InputStream in = Files.newInputStream(manifestFile)) { manifest.load(in); }
            if (!"local-invoice-backup-v1".equals(manifest.getProperty("format")))
                throw new IllegalArgumentException("备份格式不受支持");
            int count = Integer.parseInt(manifest.getProperty("count", "-1"));
            if (count < 1 || count > 100_000) throw new IllegalArgumentException("备份文件数无效");
            for (int index = 0; index < count; index++) {
                String name = manifest.getProperty("file." + index + ".path");
                String hash = manifest.getProperty("file." + index + ".sha256");
                if (name == null || hash == null) throw new IllegalArgumentException("备份清单不完整");
                Path file = temporary.resolve(name).normalize();
                if (!file.startsWith(temporary) || !Files.isRegularFile(file)
                        || !hash.equals(AppService.sha256(file)))
                    throw new IllegalArgumentException("备份文件损坏: " + name);
            }
            if (entries.size() != count + 1) throw new IllegalArgumentException("备份清单与文件数量不一致");
            if (!Files.isRegularFile(temporary.resolve("invoice-manager.db")))
                throw new IllegalArgumentException("备份数据库缺失");
        } finally {
            if (destination == null) deleteTree(temporary);
        }
    }

    private static void validateDatabase(Path staging) throws Exception {
        Path dbFile = staging.resolve("invoice-manager.db");
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             Statement st = db.createStatement(); ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1)))
                throw new IllegalArgumentException("备份数据库校验失败");
        }
        Database check = new Database(staging);
        for (Invoice invoice : check.invoices()) {
            Path original = staging.resolve(invoice.originalPath).normalize();
            if (!original.startsWith(staging.resolve("originals")) || !Files.isRegularFile(original))
                throw new IllegalArgumentException("备份缺少发票原件: " + invoice.originalName);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
