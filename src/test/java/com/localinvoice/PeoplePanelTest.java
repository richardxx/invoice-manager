package com.localinvoice;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.YearMonth;
import java.util.UUID;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeoplePanelTest {
    @TempDir Path temp;

    @Test void documentTypesAreFixedAndLegacyIdentityCardIsRecognized() {
        assertEquals(java.util.List.of("居民身份证", "中华人民共和国因私护照", "外国护照",
                "港澳居民来往内地通行证（香港）"), PaymentDetails.IDENTITY_TYPES);
        PaymentDetails legacy = new PaymentDetails("1", "甲", PaymentDetails.BankType.BOC,
                "2", "身份证", "3");
        assertEquals("居民身份证", legacy.identityType());
        assertTrue(legacy.missingFields().isEmpty());
        PaymentDetails unsupported = new PaymentDetails("1", "甲", PaymentDetails.BankType.BOC,
                "2", "其他证件", "3");
        assertThrows(IllegalArgumentException.class, unsupported::requireComplete);
    }

    @Test void doubleClickingNameOpensAnUnclippedEditorWithDocumentDropdown() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        AppService app = new AppService(temp.resolve("dialog-data"), temp);
        app.addPerson("双击测试", null);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("People editor test");
            try {
                PeoplePanel panel = new PeoplePanel(null, app);
                panel.refresh();
                frame.add(panel);
                frame.setSize(900, 600);
                frame.setVisible(true);
                JTable table = (JTable) ((JScrollPane) panel.getComponent(1)).getViewport().getView();
                Point point = table.getCellRect(0, 0, true).getLocation();
                Timer inspect = new Timer(150, event -> {
                    JDialog dialog = Arrays.stream(Window.getWindows())
                            .filter(window -> window instanceof JDialog candidate && candidate.isVisible()
                                    && "编辑人员".equals(candidate.getTitle()))
                            .map(window -> (JDialog) window).findFirst().orElse(null);
                    try {
                        assertNotNull(dialog);
                        assertTrue(dialog.getWidth() >= 700);
                        assertTrue(dialog.getHeight() >= 540);
                        JComboBox<?> identityTypes = findIdentityTypes(dialog);
                        assertNotNull(identityTypes);
                        assertEquals(4, identityTypes.getItemCount());
                        assertEquals("居民身份证", identityTypes.getSelectedItem());
                    } catch (Throwable error) { failure.set(error); }
                    finally { if (dialog != null) dialog.dispose(); }
                });
                inspect.setRepeats(false);
                inspect.start();
                table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_CLICKED,
                        System.currentTimeMillis(), 0, point.x + 10, point.y + 10, 2, false, MouseEvent.BUTTON1));
            } catch (Throwable error) { failure.set(error); }
            finally { frame.dispose(); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static JComboBox<?> findIdentityTypes(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JComboBox<?> combo && combo.getItemCount() == 4
                    && "居民身份证".equals(combo.getItemAt(0))) return combo;
            if (child instanceof Container nested) {
                JComboBox<?> match = findIdentityTypes(nested);
                if (match != null) return match;
            }
        }
        return null;
    }

    @Test void showsCompletedTotalBesideAvatarAndName() throws Exception {
        AppService app = new AppService(temp.resolve("data"), temp);
        Person person = app.addPerson("报销人", "preset:sky");
        Invoice invoice = new Invoice();
        invoice.id = "paid";
        invoice.originalPath = "originals/paid.pdf";
        invoice.originalName = "paid.pdf";
        invoice.sha256 = UUID.randomUUID().toString();
        invoice.importedAt = "2026-09-29T00:00:00Z";
        invoice.ownerId = person.id();
        invoice.reimbursementMonth = YearMonth.of(2026, 9);
        invoice.amountCents = 12345L;
        invoice.category = Category.TRANSPORT;
        invoice.reviewStatus = ReviewStatus.READY;
        app.database().insertInvoice(invoice);
        app.setMonthCompleted(YearMonth.of(2026, 9), true);

        SwingUtilities.invokeAndWait(() -> {
            try {
                PeoplePanel panel = new PeoplePanel(null, app);
                panel.refresh();
                JTable table = (JTable) ((JScrollPane) panel.getComponent(1)).getViewport().getView();
                assertEquals("¥123.45", table.getValueAt(0, 1));
                JLabel renderer = (JLabel) table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0);
                assertTrue(renderer.getText().contains("报销人"));
                assertNotNull(renderer.getIcon());
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }
}
