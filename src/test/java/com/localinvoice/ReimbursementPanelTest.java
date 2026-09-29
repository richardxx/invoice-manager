package com.localinvoice;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReimbursementPanelTest {
    @TempDir Path temp;

    @Test void pendingAssignedInvoicesShowTheirKnownAmountsInMonthlyGrid() throws Exception {
        AppService app = new AppService(temp.resolve("data"), temp);
        Person owner = app.addPerson("张三", "preset:sky");
        YearMonth month = YearMonth.of(2026, 9);
        Category[] categories = {Category.TRANSPORT, Category.DINING, Category.DINING, Category.LODGING};
        long[] amounts = {17753L, 10880L, 46800L, 147200L};
        for (int index = 0; index < 4; index++) {
            Invoice invoice = new Invoice();
            invoice.id = UUID.randomUUID().toString();
            invoice.originalPath = "originals/" + invoice.id + ".pdf";
            invoice.originalName = "pending-" + index + ".pdf";
            invoice.sha256 = UUID.randomUUID().toString();
            invoice.importedAt = Instant.now().toString();
            invoice.ownerId = owner.id();
            invoice.reimbursementMonth = month;
            invoice.category = categories[index];
            invoice.amountCents = amounts[index];
            invoice.reviewStatus = ReviewStatus.NEEDS_REVIEW;
            app.database().insertInvoice(invoice);
        }
        SwingUtilities.invokeAndWait(() -> {
            ReimbursementPanel panel = new ReimbursementPanel(null, app);
            panel.changeMonth(month);
            JTable table = (JTable) ((JScrollPane) panel.getComponent(1)).getViewport().getView();
            assertTrue(table.getCellSelectionEnabled());
            assertEquals(1, table.getRowCount());
            assertEquals("张三", table.getValueAt(0, 0));
            assertEquals("4 张", table.getValueAt(0, Category.values().length + 1));
            assertEquals("4 张", table.getValueAt(0, Category.values().length + 2));
            assertEquals(Ui.money(17753L), table.getValueAt(0, Category.TRANSPORT.ordinal() + 1));
            assertEquals(Ui.money(57680L), table.getValueAt(0, Category.DINING.ordinal() + 1));
            assertEquals(Ui.money(147200L), table.getValueAt(0, Category.LODGING.ordinal() + 1));
            assertEquals(Ui.money(222633L), table.getValueAt(0, table.getColumnCount() - 1));
            assertEquals(4, assertDoesNotThrow(() -> panel.detailInvoices(0, 0)).size());
            assertEquals(4, assertDoesNotThrow(() -> panel.detailInvoices(0, table.getColumnCount() - 1)).size());
            assertEquals(4, assertDoesNotThrow(() -> panel.detailInvoices(0, Category.values().length + 1)).size());
            assertEquals(4, assertDoesNotThrow(() -> panel.detailInvoices(0, Category.values().length + 2)).size());
            assertEquals(1, assertDoesNotThrow(() -> panel.detailInvoices(0, Category.LODGING.ordinal() + 1)).size());
            assertTrue(((javax.swing.JLabel) panel.getComponent(2)).getText().contains("含待核对"));
            JButton finish = java.util.Arrays.stream(((JPanel) panel.getComponent(0)).getComponents())
                    .filter(component -> component instanceof JButton button && button.getText().equals("完成报销"))
                    .map(component -> (JButton) component).findFirst().orElseThrow();
            assertTrue(finish.isEnabled());
            try {
                app.setMonthCompleted(month, true);
                panel.refresh();
            } catch (Exception error) { throw new RuntimeException(error); }
            assertEquals("撤销报销", finish.getText());
        });
    }
}
