package com.localinvoice;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Keeps the active data location separate from the data being moved. */
public final class WorkDirectoryService {
    private final Path defaultRoot;
    private final Path configuration;
    private final Path lockPath;

    public WorkDirectoryService(Path defaultRoot, Path bootstrapDirectory) {
        this.defaultRoot = defaultRoot.toAbsolutePath().normalize();
        Path bootstrap = bootstrapDirectory.toAbsolutePath().normalize();
        this.configuration = bootstrap.resolve("workspace.properties");
        this.lockPath = bootstrap.resolve("instance.lock");
    }

    public Path current() throws Exception {
        Properties properties = new Properties();
        if (Files.isRegularFile(configuration)) {
            try (InputStream input = Files.newInputStream(configuration)) { properties.load(input); }
        }
        String value = properties.getProperty("workDirectory", "").trim();
        if (value.isEmpty()) return defaultRoot;
        Path selected = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(selected.resolve("invoice-manager.db")))
            throw new IllegalStateException("已设置的工作目录缺少发票数据库，请检查目录是否已连接: " + selected);
        return selected;
    }

    public Lock acquireLock() throws Exception {
        Files.createDirectories(lockPath.getParent());
        FileChannel channel = FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE);
        try {
            for (int attempt = 0; attempt < 120; attempt++) {
                try {
                    FileLock lock = channel.tryLock();
                    if (lock != null) return new Lock(channel, lock);
                } catch (java.nio.channels.OverlappingFileLockException ignored) { }
                Thread.sleep(500);
            }
            throw new IllegalStateException("已有一个发票管理程序正在运行，请关闭后重试");
        } catch (Exception error) {
            channel.close();
            throw error;
        }
    }

    public Path validateDestination(Path requested) throws Exception {
        if (requested == null) throw new IllegalArgumentException("请选择工作目录");
        Path source = current();
        Path target = requested.toAbsolutePath().normalize();
        if (target.equals(source)) return target;
        Path actualSource = realLocation(source);
        Path actualTarget = realLocation(target);
        Path actualBootstrap = realLocation(configuration.getParent());
        if (actualTarget.startsWith(actualSource) || actualSource.startsWith(actualTarget)
                || actualTarget.startsWith(actualBootstrap) || actualBootstrap.startsWith(actualTarget))
            throw new IllegalArgumentException("新工作目录不能与当前目录或程序配置目录相互包含");
        if (Files.exists(target)) {
            if (!Files.isDirectory(target) || Files.isSymbolicLink(target))
                throw new IllegalArgumentException("请选择普通文件夹作为新工作目录");
            try (var entries = Files.list(target)) {
                if (entries.findAny().isPresent()) throw new IllegalArgumentException("新工作目录必须为空，以免覆盖现有文件");
            }
        }
        return target;
    }

    private static Path realLocation(Path path) throws Exception {
        Path parent = path;
        List<Path> missing = new ArrayList<>();
        while (!Files.exists(parent)) {
            missing.add(parent.getFileName());
            parent = parent.getParent();
            if (parent == null) throw new IllegalArgumentException("工作目录路径无效");
        }
        Path resolved = parent.toRealPath();
        for (int index = missing.size() - 1; index >= 0; index--) resolved = resolved.resolve(missing.get(index));
        return resolved.normalize();
    }

    public void migrate(Path requested) throws Exception {
        Path source = current();
        Path target = validateDestination(requested);
        if (source.equals(target)) return;
        if (!Files.isDirectory(source) || !Files.isRegularFile(source.resolve("invoice-manager.db")))
            throw new IllegalStateException("当前工作目录缺少发票数据库，迁移已取消");
        Files.createDirectories(target);
        List<Path> sourceFiles = new ArrayList<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws java.io.IOException {
                if (attributes.isSymbolicLink()) throw new java.io.IOException("工作目录包含符号链接，无法安全迁移: " + dir);
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws java.io.IOException {
                if (!attributes.isRegularFile()) throw new java.io.IOException("工作目录包含特殊文件，无法安全迁移: " + file);
                Path copy = target.resolve(source.relativize(file));
                Files.copy(file, copy);
                sourceFiles.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        for (Path file : sourceFiles) {
            Path copy = target.resolve(source.relativize(file));
            if (Files.size(file) != Files.size(copy) || !AppService.sha256(file).equals(AppService.sha256(copy)))
                throw new IllegalStateException("迁移校验失败，原目录仍可使用: " + file.getFileName());
        }
        try (var files = Files.walk(target)) {
            if (files.filter(Files::isRegularFile).count() != sourceFiles.size())
                throw new IllegalStateException("迁移文件数量不一致，原目录仍可使用");
        }
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + target.resolve("invoice-manager.db"));
             Statement statement = database.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1)))
                throw new IllegalStateException("迁移后的数据库校验失败，原目录仍可使用");
        }
        saveCurrent(target);
    }

    private void saveCurrent(Path target) throws Exception {
        Files.createDirectories(configuration.getParent());
        Path temporary = configuration.getParent().resolve("workspace-" + UUID.randomUUID() + ".tmp");
        try {
            Properties properties = new Properties();
            properties.setProperty("workDirectory", target.toString());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "InvoiceManage active work directory");
            }
            try {
                Files.move(temporary, configuration, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, configuration, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    public record Lock(FileChannel channel, FileLock fileLock) implements AutoCloseable {
        @Override public void close() throws Exception {
            fileLock.release();
            channel.close();
        }
    }
}
