package com.localinvoice;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsServiceTest {
    @TempDir Path temp;

    @Test void windowsKeyIsProtectedAtRestAndReloadsForSameUser() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
        SettingsService settings = new SettingsService(temp);
        settings.save(new AiSettings("https://api.example.com/v1", "vision", "unique-test-secret-891"));
        assertFalse(Files.readString(temp.resolve("settings.properties")).contains("unique-test-secret-891"));
        Files.writeString(temp.resolve("settings.properties"), "\nai.enabled=true\n",
                java.nio.file.StandardOpenOption.APPEND);
        AiSettings loaded = new SettingsService(temp).current();
        assertTrue(loaded.configured());
        assertEquals("unique-test-secret-891", loaded.apiKey());
    }
}
