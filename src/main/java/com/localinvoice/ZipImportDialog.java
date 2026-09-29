package com.localinvoice;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Color;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

public final class ZipImportDialog {
    private final MainWindow window;
    private final AppService app;
    private final ZipInvoiceImportService importer;
    private final ZipInvoiceImportService.Report report;
    private final List<ZipInvoiceImportService.Entry> entries;
    private final JDialog dialog;
    private final DefaultTableModel model;
    private final JTable table;
    private final JLabel summary = new JLabel();
    private final JButton importButton = new JButton("导入所选");
    private final JButton closeButton = new JButton("关闭");
    private boolean importing;

    public ZipImportDialog(MainWindow window, AppService app, ZipInvoiceImportService importer,
                           ZipInvoiceImportService.Report report) {
        this.window = window;
        this.app = app;
        this.importer = importer;
        this.report = report;
        this.entries = report.entries();
        dialog = new JDialog(window, "ZIP 发票导入结果", true);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                if (!importing) dialog.dispose();
            }
        });
        JLabel hint = new JLabel("双击行打开 PDF；选中一项或按 Ctrl/Shift 多选未入池文件，再点“导入所选”");
        hint.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 0, 8));
        dialog.add(hint, BorderLayout.NORTH);
        model = new DefaultTableModel(new String[]{"包内路径", "识别信息", "状态", "说明"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(32);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public java.awt.Component getTableCellRendererComponent(JTable source, Object value,
                    boolean selected, boolean focused, int row, int column) {
                super.getTableCellRendererComponent(source, value, selected, focused, row, column);
                setIcon(entries.get(row).status() == ZipInvoiceImportService.Status.IMPORTED
                        ? AppIcons.of(AppIcons.Kind.CHECK, selected ? getForeground() : new Color(0x7CCF9E))
                        : null);
                return this;
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    int row = table.rowAtPoint(event.getPoint());
                    if (row >= 0) openPdf(entries.get(row));
                }
            }
        });
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(summary, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        if (!report.warnings().isEmpty()) {
            JButton warnings = new JButton("查看警告 (" + report.warnings().size() + ")");
            warnings.addActionListener(event -> showWarnings());
            actions.add(warnings);
        }
        importButton.addActionListener(event -> importSelected());
        closeButton.addActionListener(event -> { if (!importing) dialog.dispose(); });
        actions.add(importButton);
        actions.add(closeButton);
        bottom.add(actions, BorderLayout.EAST);
        dialog.add(bottom, BorderLayout.SOUTH);
        refreshRows();
        dialog.setSize(1040, 560);
        dialog.setLocationRelativeTo(window);
    }

    public void showDialog() {
        try {
            dialog.setVisible(true);
        } finally {
            dialog.dispose();
            try { report.close(); }
            catch (Exception error) { Ui.error(window, error); }
        }
    }

    private void refreshRows() {
        model.setRowCount(0);
        for (ZipInvoiceImportService.Entry entry : entries) {
            Invoice invoice = entry.recognized();
            String recognized = invoice == null ? "—" : (invoice.issuer == null ? "开票方未识别" : invoice.issuer)
                    + " · " + Ui.money(invoice.amountCents)
                    + " · " + (invoice.category == null ? "—" : invoice.category.label());
            model.addRow(new Object[]{entry.displayPath(), recognized, entry.status().label(), entry.message()});
        }
        long pending = entries.stream().filter(ZipInvoiceImportService.Entry::canImport).count();
        summary.setIcon(report.importedCount() > 0
                ? AppIcons.of(AppIcons.Kind.CHECK, new Color(0x7CCF9E)) : null);
        summary.setText("共 " + entries.size() + " 份 PDF · 已入池 " + report.importedCount()
                + " · 可补充导入 " + pending);
        importButton.setEnabled(pending > 0 && !importing);
    }

    private void openPdf(ZipInvoiceImportService.Entry entry) {
        try {
            Path file = entry.extracted();
            if (entry.invoiceId() != null) {
                Invoice invoice = app.invoice(entry.invoiceId());
                if (invoice != null) file = app.original(invoice);
            }
            if (file == null || !Files.isRegularFile(file)) {
                JOptionPane.showMessageDialog(dialog, "此 PDF 未被解压，无法打开。");
                return;
            }
            Desktop.getDesktop().open(file.toFile());
        } catch (Exception error) { Ui.error(dialog, error); }
    }

    private void importSelected() {
        List<ZipInvoiceImportService.Entry> selected = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            ZipInvoiceImportService.Entry entry = entries.get(row);
            if (entry.canImport()) selected.add(entry);
        }
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(dialog, "请先选择尚未入池的 PDF。");
            return;
        }
        importing = true;
        importButton.setEnabled(false);
        closeButton.setEnabled(false);
        summary.setText("正在补充导入 " + selected.size() + " 份 PDF…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                for (ZipInvoiceImportService.Entry entry : selected) importer.importSelected(entry);
                return null;
            }
            @Override protected void done() {
                importing = false;
                closeButton.setEnabled(true);
                try {
                    get();
                    window.refreshAll();
                    refreshRows();
                } catch (Exception error) { Ui.error(dialog, error); }
            }
        }.execute();
    }

    private void showWarnings() {
        JTextArea area = new JTextArea(String.join("\n", report.warnings()), 12, 78);
        area.setEditable(false);
        JOptionPane.showMessageDialog(dialog, new JScrollPane(area), "ZIP 处理警告", JOptionPane.WARNING_MESSAGE);
    }
}
