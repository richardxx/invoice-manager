package com.localinvoice;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkDirectoryServiceTest {
    @TempDir Path temp;

    @Test void migrationCopiesAndVerifiesDataBeforeChangingActiveDirectory() throws Exception {
        Path original = temp.resolve("original");
        WorkDirectoryService directory = new WorkDirectoryService(original, temp.resolve("bootstrap"));
        AppService app = new AppService(original, temp);
        Person person = app.addPerson("保留人员", "preset:sky");
        Files.writeString(original.resolve("originals/sample.pdf"), "preserved original bytes");
        Files.writeString(original.resolve("future-data.txt"), "future version data");
        app.settings().save(new AiSettings("https://api.openai.com/v1", "", ""));

        Path destination = temp.resolve("new-work-directory");
        assertEquals(original.toAbsolutePath(), directory.current());
        directory.migrate(destination);

        assertEquals(destination.toAbsolutePath(), directory.current());
        assertEquals("preserved original bytes", Files.readString(destination.resolve("originals/sample.pdf")));
        assertEquals("future version data", Files.readString(destination.resolve("future-data.txt")));
        assertArrayEquals(Files.readAllBytes(original.resolve("invoice-manager.db")),
                Files.readAllBytes(destination.resolve("invoice-manager.db")));
        assertNotNull(new AppService(destination, temp).database().person(person.id()));
        assertTrue(Files.isRegularFile(original.resolve("invoice-manager.db")));
        assertTrue(Files.isRegularFile(original.resolve("originals/sample.pdf")));
    }

    @Test void nonemptyOrNestedDestinationNeverChangesActiveDirectory() throws Exception {
        Path original = temp.resolve("original");
        new AppService(original, temp);
        WorkDirectoryService directory = new WorkDirectoryService(original, temp.resolve("bootstrap"));
        Path nonempty = temp.resolve("occupied");
        Files.createDirectories(nonempty);
        Files.writeString(nonempty.resolve("keep.txt"), "keep");

        assertThrows(IllegalArgumentException.class, () -> directory.migrate(nonempty));
        assertThrows(IllegalArgumentException.class, () -> directory.migrate(original.resolve("nested")));
        assertEquals(original.toAbsolutePath(), directory.current());
        assertEquals("keep", Files.readString(nonempty.resolve("keep.txt")));
    }

    @Test void unreadableDatabaseLeavesOriginalDirectorySelected() throws Exception {
        Path original = temp.resolve("original");
        Files.createDirectories(original);
        Files.writeString(original.resolve("invoice-manager.db"), "not a SQLite database");
        WorkDirectoryService directory = new WorkDirectoryService(original, temp.resolve("bootstrap"));

        assertThrows(Exception.class, () -> directory.migrate(temp.resolve("destination")));
        assertEquals(original.toAbsolutePath(), directory.current());
        assertEquals("not a SQLite database", Files.readString(original.resolve("invoice-manager.db")));
    }
}
