package com.localinvoice;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

public final class ReimbursementPanel extends JPanel {
    private final MainWindow window;
    private final AppService app;
    private final JTextField monthField = new JTextField(7);
    private final DefaultTableModel model;
    private final JTable table;
    private final JLabel summary = new JLabel();
    private final JButton finish = new JButton("完成报销");
    private YearMonth month = YearMonth.now(java.time.ZoneId.of("Asia/Shanghai"));
    private List<Person> rows = new ArrayList<>();

    public ReimbursementPanel(MainWindow window, AppService app) {
        super(new BorderLayout(8, 8));
        this.window = window;
        this.app = app;
        setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 18, 18, 18));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton previous = new JButton("上月", AppIcons.of(AppIcons.Kind.CHEVRON_LEFT, RetroArt.BLUE));
        JButton next = new JButton("下月", AppIcons.of(AppIcons.Kind.CHEVRON_RIGHT, RetroArt.BLUE));
        next.setHorizontalTextPosition(JButton.LEFT);
        JButton export = new JButton("导出发票包");
        JButton bankExport = new JButton("导出银行Excel");
        monthField.setText(month.toString());
        previous.addActionListener(event -> changeMonth(month.minusMonths(1)));
        next.addActionListener(event -> changeMonth(month.plusMonths(1)));
        monthField.addActionListener(event -> {
            try { changeMonth(YearMonth.parse(monthField.getText().trim())); }
            catch (Exception error) { JOptionPane.showMessageDialog(this, "月份格式应为 YYYY-MM"); }
        });
        monthField.setToolTipText("输入 YYYY-MM，按回车切换月份");
        finish.addActionListener(event -> changeReimbursementState());
        export.addActionListener(event -> export());
        bankExport.addActionListener(event -> exportBankExcel());
        top.add(new JLabel("报销月份"));
        top.add(previous);
        top.add(monthField);
        top.add(next);
        top.add(finish);
        top.add(export);
        top.add(bankExport);
        add(top, BorderLayout.NORTH);
        String[] columns = new String[Category.values().length + 4];
        columns[0] = "人员";
        for (Category category : Category.values()) columns[category.ordinal() + 1] = category.label();
        columns[Category.values().length + 1] = "已归属";
        columns[Category.values().length + 2] = "待核对";
        columns[columns.length - 1] = "总额";
        model = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(model);
        table.setRowHeight(38);
        table.setCellSelectionEnabled(true);
        table.setAutoCreateRowSorter(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.setToolTipText("双击姓名或总额查看全部发票；双击类型金额查看该类型发票");
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() != 2 || !javax.swing.SwingUtilities.isLeftMouseButton(event)) return;
                int row = table.rowAtPoint(event.getPoint());
                int column = table.columnAtPoint(event.getPoint());
                if (row >= 0 && column >= 0) showInvoices(row, column);
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(summary, BorderLayout.SOUTH);
    }

    public void refresh() throws Exception {
        rows = app.activePeople(month);
        Map<String, long[]> totals = app.displayTotals(month);
        List<Invoice> assigned = app.assignedInMonth(month);
        boolean completed = app.monthCompleted(month);
        finish.setText(completed ? "撤销报销" : "完成报销");
        finish.setEnabled(!assigned.isEmpty());
        model.setRowCount(0);
        long grandTotal = 0;
        int pendingCount = 0;
        for (Invoice invoice : assigned) if (!invoice.counted()) pendingCount++;
        int confirmedCount = assigned.size() - pendingCount;
        long reimbursedCount = assigned.stream().filter(invoice -> invoice.reimbursedAt != null).count();
        for (Person person : rows) {
            long[] values = totals.get(person.id());
            Object[] cells = new Object[Category.values().length + 4];
            cells[0] = person.name();
            long rowTotal = 0;
            for (Category category : Category.values()) {
                long cents = values == null ? 0 : values[category.ordinal()];
                cells[category.ordinal() + 1] = Ui.money(cents);
                rowTotal = Math.addExact(rowTotal, cents);
            }
            int ownerCount = 0;
            int ownerPending = 0;
            for (Invoice invoice : assigned) {
                if (person.id().equals(invoice.ownerId)) {
                    ownerCount++;
                    if (!invoice.counted()) ownerPending++;
                }
            }
            cells[Category.values().length + 1] = ownerCount + " 张";
            cells[Category.values().length + 2] = ownerPending + " 张";
            cells[cells.length - 1] = Ui.money(rowTotal);
            grandTotal = Math.addExact(grandTotal, rowTotal);
            model.addRow(cells);
        }
        summary.setText("本月已归属 " + assigned.size() + " 张 · 已报销 " + reimbursedCount + " 张 · 待核对 " + pendingCount
                + " 张 · 已确认 " + confirmedCount + " 张 · 金额合计（含待核对） " + Ui.money(grandTotal)
                + " · 导出仅含已确认发票");
    }

    void changeMonth(YearMonth selected) {
        month = selected;
        monthField.setText(month.toString());
        try { refresh(); } catch (Exception error) { Ui.error(this, error); }
    }

    List<Invoice> detailInvoices(int row, int column) throws Exception {
        Person owner = rows.get(row);
        Category category = categoryForColumn(column);
        boolean pendingOnly = column == Category.values().length + 2;
        List<Invoice> invoices = new ArrayList<>();
        for (Invoice invoice : app.assignedInMonth(month)) {
            if (owner.id().equals(invoice.ownerId)
                    && (category == null || invoice.category == category)
                    && (!pendingOnly || !invoice.counted())) invoices.add(invoice);
        }
        return invoices;
    }

    private static Category categoryForColumn(int column) {
        return column >= 1 && column <= Category.values().length ? Category.values()[column - 1] : null;
    }

    private void showInvoices(int row, int column) {
        try {
            Person owner = rows.get(row);
            Category category = categoryForColumn(column);
            boolean pendingOnly = column == Category.values().length + 2;
            List<Invoice> invoices = detailInvoices(row, column);
            long subtotal = 0;
            for (Invoice invoice : invoices)
                if (invoice.amountCents != null && invoice.amountCents > 0 && invoice.category != null)
                    subtotal = Math.addExact(subtotal, invoice.amountCents);
            String title = category != null ? category.label() : pendingOnly ? "待核对" : "全部已归属";
            JDialog dialog = new JDialog(window, owner.name() + " · " + title + " · " + month, true);
            dialog.setLayout(new BorderLayout(8, 8));
            String[] names = {"开票日期", "开票方", "金额", "类型", "核对状态", "报销状态", "文件名"};
            DefaultTableModel detail = new DefaultTableModel(names, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            for (Invoice invoice : invoices) detail.addRow(new Object[]{invoice.issueDate, invoice.issuer,
                    Ui.money(invoice.amountCents), invoice.category == null ? "—" : invoice.category.label(),
                    invoice.reviewStatus.label(), invoice.reimbursedAt == null ? "待报销" : "已报销",
                    invoice.originalName});
            JTable list = new JTable(detail);
            list.setRowHeight(30);
            dialog.add(new JScrollPane(list), BorderLayout.CENTER);
            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            bottom.add(new JLabel("金额小计（含待核对） " + Ui.money(subtotal) + " · 共 " + invoices.size() + " 张"));
            JButton open = new JButton("打开原件");
            JButton edit = new JButton("查看/编辑");
            open.addActionListener(event -> {
                int index = list.getSelectedRow();
                if (index < 0) return;
                try { java.awt.Desktop.getDesktop().open(app.original(invoices.get(index)).toFile()); }
                catch (Exception error) { Ui.error(dialog, error); }
            });
            edit.addActionListener(event -> {
                int index = list.getSelectedRow();
                if (index < 0) return;
                InvoiceEditor.show(window, app, invoices.get(index), window::refreshAll);
                dialog.dispose();
            });
            bottom.add(open);
            bottom.add(edit);
            dialog.add(bottom, BorderLayout.SOUTH);
            dialog.setSize(760, 420);
            dialog.setLocationRelativeTo(window);
            dialog.setVisible(true);
        } catch (Exception error) { Ui.error(this, error); }
    }

    private void changeReimbursementState() {
        try {
            YearMonth typed = YearMonth.parse(monthField.getText().trim());
            if (!typed.equals(month)) {
                changeMonth(typed);
                return;
            }
            boolean completed = app.monthCompleted(month);
            String message = completed ? "撤销 " + month + " 的报销状态？本月发票将恢复为待报销。"
                    : "确认 " + month + " 的所有发票均已完成报销？本月已归属发票将标记为已报销。";
            if (JOptionPane.showConfirmDialog(this, message, completed ? "撤销报销" : "完成报销",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            app.setMonthCompleted(month, !completed);
            window.refreshAll();
        } catch (Exception error) { Ui.error(this, error); }
    }

    private void export() {
        try {
            YearMonth selectedMonth;
            try { selectedMonth = YearMonth.parse(monthField.getText().trim()); }
            catch (Exception invalid) { throw new IllegalArgumentException("月份格式应为 YYYY-MM"); }
            if (!selectedMonth.equals(month)) changeMonth(selectedMonth);
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("选择发票导出的上级目录");
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path parent = chooser.getSelectedFile().toPath();
            Path destination = ExportService.monthFolder(selectedMonth, parent);
            if (Files.exists(destination))
                throw new IllegalArgumentException("月份文件夹已存在，请选择另一个上级目录：" + destination);
            ExportService exporter = new ExportService(app);
            List<ExportService.ExportItem> preview = exporter.previewMonth(selectedMonth);
            List<ExportService.ExportItem> approved = ExportPreviewDialog.show(window, selectedMonth, preview);
            if (approved == null) return;
            new SwingWorker<Integer, Void>() {
                @Override protected Integer doInBackground() throws Exception {
                    return exporter.exportMonth(selectedMonth, parent, approved);
                }
                @Override protected void done() {
                    try { JOptionPane.showMessageDialog(ReimbursementPanel.this,
                            "已导出 " + get() + " 张发票至\n" + destination); }
                    catch (Exception error) { Ui.error(ReimbursementPanel.this, error); }
                }
            }.execute();
        } catch (Exception error) { Ui.error(this, error); }
    }

    private void exportBankExcel() {
        try {
            YearMonth selectedMonth;
            try { selectedMonth = YearMonth.parse(monthField.getText().trim()); }
            catch (Exception invalid) { throw new IllegalArgumentException("月份格式应为 YYYY-MM"); }
            if (!selectedMonth.equals(month)) changeMonth(selectedMonth);
            BankExcelExportService exporter = new BankExcelExportService(app);
            List<BankExcelExportService.PaymentRow> rows = exporter.previewMonth(selectedMonth);
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("保存 " + rows.size() + " 人的银行报销表");
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Excel 97-2003 工作簿 (*.xls)", "xls"));
            chooser.setSelectedFile(Path.of(selectedMonth.toString().replace("-", "") + "-银行报销.xls").toFile());
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path destination = chooser.getSelectedFile().toPath();
            if (!destination.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xls"))
                destination = destination.resolveSibling(destination.getFileName() + ".xls");
            if (Files.exists(destination) && JOptionPane.showConfirmDialog(this, "文件已存在，覆盖吗？", "确认覆盖",
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            Path output = destination;
            new SwingWorker<Integer, Void>() {
                @Override protected Integer doInBackground() throws Exception {
                    return exporter.exportMonth(selectedMonth, output);
                }
                @Override protected void done() {
                    try { JOptionPane.showMessageDialog(ReimbursementPanel.this,
                            "已导出 " + get() + " 人的银行报销表至\n" + output); }
                    catch (Exception error) { Ui.error(ReimbursementPanel.this, error); }
                }
            }.execute();
        } catch (Exception error) { Ui.error(this, error); }
    }
}
