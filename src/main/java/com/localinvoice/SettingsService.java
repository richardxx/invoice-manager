package com.localinvoice;

import com.sun.jna.platform.win32.Crypt32Util;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;

public final class SettingsService {
    private final Path file;
    private AiSettings current;

    public SettingsService(Path dataRoot) throws Exception {
        this.file = dataRoot.resolve("settings.properties");
        this.current = load();
    }

    public synchronized AiSettings current() { return current; }

    public synchronized void save(AiSettings value) throws Exception {
        if (value.configured()) validate(value);
        Properties properties = new Properties();
        properties.setProperty("ai.baseUrl", value.baseUrl());
        properties.setProperty("ai.model", value.model());
        if (!value.apiKey().isBlank() && windows()) {
            byte[] encrypted = Crypt32Util.cryptProtectData(value.apiKey().getBytes(StandardCharsets.UTF_8));
            properties.setProperty("ai.protectedKey", Base64.getEncoder().encodeToString(encrypted));
        }
        Path temporary = Files.createTempFile(file.getParent(), "settings-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "InvoiceManage settings; API key is Windows DPAPI protected");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        current = value;
    }

    private AiSettings load() throws Exception {
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
        }
        String key = "";
        String protectedKey = properties.getProperty("ai.protectedKey", "");
        if (windows() && !protectedKey.isBlank()) {
            try {
                key = new String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(protectedKey)),
                        StandardCharsets.UTF_8);
            } catch (RuntimeException ignored) {
                // A key protected by a different Windows account requires re-entry.
            }
        }
        return new AiSettings(properties.getProperty("ai.baseUrl", ""),
                properties.getProperty("ai.model", ""), key);
    }

    static void validate(AiSettings value) {
        if (value.model().isBlank() || value.apiKey().isBlank())
            throw new IllegalArgumentException("使用 AI 识别前，请填写模型名称和 API Key");
        URI uri;
        try { uri = URI.create(value.baseUrl()); }
        catch (Exception error) { throw new IllegalArgumentException("Base URL 无效"); }
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (!"https".equalsIgnoreCase(uri.getScheme())
                && !("http".equalsIgnoreCase(uri.getScheme())
                && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.equals("[::1]")))))
            throw new IllegalArgumentException("Base URL 须为 HTTPS 地址；本机服务可使用 HTTP");
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
