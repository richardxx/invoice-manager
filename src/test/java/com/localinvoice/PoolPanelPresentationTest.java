package com.localinvoice;

import static org.junit.jupiter.api.Assertions.*;
import com.formdev.flatlaf.FlatDarkLaf;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.UUID;
import javax.swing.JScrollPane;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PoolPanelPresentationTest {
    @TempDir Path temp;

    @Test void poolShowsUnassignedFirstWithDistinctRowStyleAndBulkSelection() throws Exception {
        AppService app = new AppService(temp.resolve("data"), temp);
        Person owner = app.addPerson("归属人", null);
        app.database().insertInvoice(invoice("assigned", "2026-09-28T10:00:00Z"));
        app.database().insertInvoice(invoice("unassigned", "2026-09-27T10:00:00Z"));
        app.assign("assigned", owner.id(), YearMonth.of(2026, 9));

        SwingUtilities.invokeAndWait(() -> {
            try {
                PoolPanel panel = new PoolPanel(null, app);
                panel.refresh();
                JButton actions = (JButton) ((JPanel) panel.getComponent(0)).getComponent(1);
                assertEquals("所选发票操作", actions.getText());
                assertNotNull(actions.getIcon());
                JTable table = (JTable) ((JScrollPane) panel.getComponent(1)).getViewport().getView();
                assertEquals(2, table.getRowCount());
                assertEquals("unassigned.pdf", table.getValueAt(0, 0));
                assertEquals("未归属", table.getValueAt(0, 6));
                assertEquals("归属人", table.getValueAt(1, 6));
                assertNotNull(((javax.swing.JLabel) table.prepareRenderer(table.getCellRenderer(0, 6), 0, 6)).getIcon());
                assertNotNull(((javax.swing.JLabel) table.prepareRenderer(table.getCellRenderer(1, 6), 1, 6)).getIcon());
                Color unassigned = table.getCellRenderer(0, 0)
                        .getTableCellRendererComponent(table, table.getValueAt(0, 0), false, false, 0, 0)
                        .getBackground();
                Color assigned = table.getCellRenderer(1, 0)
                        .getTableCellRendererComponent(table, table.getValueAt(1, 0), false, false, 1, 0)
                        .getBackground();
                assertNotEquals(unassigned, assigned);
                assertEquals(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION,
                        table.getSelectionModel().getSelectionMode());
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }

    @Test void rightClickAndTableRenderingWorkWithFlatLaf() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        AppService app = new AppService(temp.resolve("popup-data"), temp);
        app.database().insertInvoice(invoice("popup", "2026-09-28T11:00:00Z"));

        SwingUtilities.invokeAndWait(() -> {
            var previous = UIManager.getLookAndFeel();
            JFrame frame = null;
            try {
                FlatDarkLaf.setup();
                PoolPanel panel = new PoolPanel(null, app);
                panel.refresh();
                frame = new JFrame("Popup regression");
                frame.add(panel);
                frame.setSize(900, 500);
                frame.setVisible(true);
                JTable table = (JTable) ((JScrollPane) panel.getComponent(1)).getViewport().getView();
                Point point = table.getCellRect(0, 0, true).getLocation();
                table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_RELEASED,
                        System.currentTimeMillis(), MouseEvent.BUTTON3_DOWN_MASK,
                        point.x + 8, point.y + 8, 1, true, MouseEvent.BUTTON3));
                assertNotNull(table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0));
            } catch (Exception error) { throw new RuntimeException(error); }
            finally {
                if (frame != null) frame.dispose();
                try { UIManager.setLookAndFeel(previous); }
                catch (Exception error) { throw new RuntimeException(error); }
            }
        });
    }

    private static Invoice invoice(String id, String importedAt) {
        Invoice invoice = new Invoice();
        invoice.id = id;
        invoice.originalPath = "originals/" + id + ".pdf";
        invoice.originalName = id + ".pdf";
        invoice.sha256 = UUID.randomUUID().toString();
        invoice.importedAt = importedAt;
        invoice.reviewStatus = ReviewStatus.NEEDS_REVIEW;
        return invoice;
    }
}
