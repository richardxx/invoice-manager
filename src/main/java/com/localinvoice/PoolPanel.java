package com.localinvoice;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.filechooser.FileNameExtensionFilter;

public final class PoolPanel extends JPanel {
    static final Comparator<Invoice> DISPLAY_ORDER = Comparator
            .comparing((Invoice invoice) -> invoice.ownerId != null)
            .thenComparing((Invoice invoice) -> invoice.importedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(invoice -> invoice.id);
    private final MainWindow window;
    private final AppService app;
    private final DefaultTableModel model;
    private final JTable table;
    private final JTextField search = new JTextField(18);
    private final JComboBox<Object> categoryFilter = new JComboBox<>();
    private final JComboBox<Object> statusFilter = new JComboBox<>();
    private final JComboBox<Object> ownerFilter = new JComboBox<>();
    private final JLabel progress = new JLabel("拖入 PDF 或 ZIP，或点击添加发票");
    private final JButton statusDetails = new JButton();
    private final Timer spinner;
    private String fullError;
    private boolean busy;
    private int frame;
    private List<Invoice> all = new ArrayList<>();
    private List<Invoice> shown = new ArrayList<>();
    private Map<String, Person> people = new HashMap<>();

    public PoolPanel(MainWindow window, AppService app) {
        super(new BorderLayout(8, 8));
        this.window = window;
        this.app = app;
        setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 18, 18, 18));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton add = new JButton("添加发票");
        JButton actions = new JButton("所选发票操作");
        actions.setIcon(AppIcons.of(AppIcons.Kind.CHEVRON_DOWN, RetroArt.BLUE));
        actions.setHorizontalTextPosition(JButton.LEFT);
        add.addActionListener(event -> chooseFiles());
        actions.addActionListener(event -> {
            List<Invoice> selected = selectedInvoices();
            if (!selected.isEmpty()) menu(selected).show(actions, 0, actions.getHeight());
        });
        controls.add(add);
        controls.add(actions);
        controls.add(new JLabel("搜索"));
        controls.add(search);
        controls.add(categoryFilter);
        controls.add(statusFilter);
        controls.add(ownerFilter);
        add(controls, BorderLayout.NORTH);
        model = new DefaultTableModel(new String[]{"文件名", "开票方", "金额", "类型", "开票日期", "报销月", "归属", "核对", "报销状态", "识别"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getSelectionModel().addListSelectionListener(event -> actions.setText(
                table.getSelectedRowCount() == 0 ? "所选发票操作"
                        : "所选发票操作 (" + table.getSelectedRowCount() + ")"));
        table.setRowHeight(34);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public java.awt.Component getTableCellRendererComponent(JTable source, Object value,
                    boolean selected, boolean focused, int row, int column) {
                super.getTableCellRendererComponent(source, value, selected, focused, row, column);
                setIcon(null);
                if (!selected && row < shown.size()) {
                    boolean assigned = shown.get(row).ownerId != null;
                    setBackground(new Color(assigned ? 0xEEE9D9 : 0xE2F1E8));
                    setForeground(RetroArt.INK);
                    if (column == 6) setForeground(new Color(assigned ? 0x5B6F70 : 0x236C73));
                }
                if (row < shown.size() && column == 6) {
                    boolean assigned = shown.get(row).ownerId != null;
                    setIcon(AppIcons.of(assigned ? AppIcons.Kind.CHECK : AppIcons.Kind.DOT,
                            selected ? getForeground() : new Color(assigned ? 0x5B6F70 : 0x236C73)));
                } else if (row < shown.size() && column == 8 && shown.get(row).reimbursedAt != null) {
                    setIcon(AppIcons.of(AppIcons.Kind.CHECK, selected ? getForeground() : new Color(0x236C73)));
                }
                setFont(source.getFont().deriveFont(column == 6 ? Font.BOLD : Font.PLAIN));
                return this;
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { maybeMenu(event); }
            @Override public void mouseReleased(MouseEvent event) { maybeMenu(event); }
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && !event.isPopupTrigger()) {
                    int row = table.rowAtPoint(event.getPoint());
                    if (row >= 0 && row < shown.size()) edit(shown.get(row));
                }
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        add(scroll, BorderLayout.CENTER);
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        statusBar.add(progress);
        statusDetails.setVisible(false);
        statusDetails.addActionListener(event -> showFullError());
        statusBar.add(statusDetails);
        add(statusBar, BorderLayout.SOUTH);
        spinner = new Timer(150, event -> {
            if (busy) progress.setIcon(AppIcons.spinner(new Color(0x236C73), frame++));
        });

        TransferHandler drop = new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @Override public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    @SuppressWarnings("unchecked") List<File> files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    importFiles(files.stream().map(File::toPath).toList());
                    return true;
                } catch (Exception error) { Ui.error(PoolPanel.this, error); return false; }
            }
        };
        setTransferHandler(drop);
        table.setTransferHandler(drop);
        scroll.setTransferHandler(drop);
        categoryFilter.addItem("全部类型");
        for (Category category : Category.values()) categoryFilter.addItem(category);
        statusFilter.addItem("全部状态");
        for (ReviewStatus status : ReviewStatus.values()) statusFilter.addItem(status);
        categoryFilter.addActionListener(event -> applyFilters());
        statusFilter.addActionListener(event -> applyFilters());
        ownerFilter.addActionListener(event -> applyFilters());
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilters(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilters(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilters(); }
        });
    }

    public void refresh() throws Exception {
        Object selectedOwner = ownerFilter.getSelectedItem();
        String selectedId = selectedOwner instanceof Person person ? person.id() : null;
        boolean unassigned = "未归属".equals(selectedOwner);
        people = new HashMap<>();
        ownerFilter.removeAllItems();
        ownerFilter.addItem("全部归属");
        ownerFilter.addItem("未归属");
        if (unassigned) ownerFilter.setSelectedItem("未归属");
        for (Person person : app.people()) {
            people.put(person.id(), person);
            ownerFilter.addItem(person);
            if (person.id().equals(selectedId)) ownerFilter.setSelectedItem(person);
        }
        all = new ArrayList<>(app.invoices());
        all.sort(DISPLAY_ORDER);
        applyFilters();
    }

    private void applyFilters() {
        if (model == null) return;
        Object category = categoryFilter.getSelectedItem();
        Object status = statusFilter.getSelectedItem();
        Object owner = ownerFilter.getSelectedItem();
        String query = search.getText().trim().toLowerCase();
        shown = new ArrayList<>();
        model.setRowCount(0);
        for (Invoice invoice : all) {
            if (category instanceof Category c && invoice.category != c) continue;
            if (status instanceof ReviewStatus s && invoice.reviewStatus != s) continue;
            if (owner instanceof Person p && !p.id().equals(invoice.ownerId)) continue;
            if ("未归属".equals(owner) && invoice.ownerId != null) continue;
            String ownerName = invoice.ownerId == null ? "" : people.getOrDefault(invoice.ownerId,
                    new Person("", "", null, false)).name();
            String searchable = (invoice.originalName + " " + safe(invoice.issuer) + " " + ownerName + " "
                    + safe(invoice.invoiceNumber)).toLowerCase();
            if (!searchable.contains(query)) continue;
            shown.add(invoice);
            model.addRow(new Object[]{invoice.originalName, safe(invoice.issuer), Ui.money(invoice.amountCents),
                    invoice.category == null ? "—" : invoice.category.label(),
                    invoice.issueDate == null ? "—" : invoice.issueDate.toString(),
                    invoice.reimbursementMonth == null ? "—" : invoice.reimbursementMonth.toString(),
                    invoice.ownerId == null ? "未归属" : (ownerName.isBlank() ? "未知人员" : ownerName),
                    invoice.reviewStatus.label(),
                    invoice.reimbursedAt == null ? "待报销" : "已报销",
                    invoice.recognitionSource == null ? "—" : invoice.recognitionSource});
        }
        if (!busy && fullError == null) {
            progress.setIcon(null);
            progress.setText("发票池共 " + all.size() + " 张 · 当前显示 " + shown.size() + " 张"
                    + (app.ocrAvailable() ? "" : " · OCR语言数据不可用，扫描件需人工核对"));
        }
    }

    private static String safe(String text) { return text == null ? "—" : text; }

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("发票 PDF 或 ZIP 压缩包", "pdf", "zip"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        importFiles(java.util.Arrays.stream(chooser.getSelectedFiles()).map(File::toPath).toList());
    }

    private void importFiles(List<Path> files) {
        if (files.isEmpty()) return;
        if (busy) {
            JOptionPane.showMessageDialog(this, "请等待当前识别或 ZIP 扫描完成后再导入。");
            return;
        }
        List<Path> archives = files.stream().filter(path -> path.getFileName().toString()
                .toLowerCase(Locale.ROOT).endsWith(".zip")).toList();
        List<Path> pdfs = files.stream().filter(path -> !archives.contains(path)).toList();
        if (pdfs.isEmpty()) importArchives(archives);
        else importPdfFiles(pdfs, () -> importArchives(archives));
    }

    private void importPdfFiles(List<Path> files, Runnable afterward) {
        progress.setText("正在导入 " + files.size() + " 个 PDF 文件...");
        new SwingWorker<List<ImportResult>, ImportResult>() {
            @Override protected List<ImportResult> doInBackground() {
                List<ImportResult> results = new ArrayList<>();
                for (Path file : files) {
                    javax.swing.SwingUtilities.invokeLater(() -> progress.setText("识别中: " + file.getFileName()));
                    ImportResult result = app.importPdf(file);
                    results.add(result);
                    publish(result);
                }
                return results;
            }
            @Override protected void process(List<ImportResult> chunks) {
                ImportResult last = chunks.get(chunks.size() - 1);
                progress.setText("已处理: " + last.filename() + " · " + last.message());
            }
            @Override protected void done() {
                try {
                    List<ImportResult> results = get();
                    window.refreshAll();
                    long count = results.stream().filter(ImportResult::success).count();
                    StringBuilder report = new StringBuilder("成功 " + count + " / " + results.size() + "\n\n");
                    for (ImportResult result : results) report.append(result.filename()).append("：")
                            .append(result.message()).append('\n');
                    JTextArea text = new JTextArea(report.toString(), Math.min(18, results.size() + 3), 60);
                    text.setEditable(false);
                    JOptionPane.showMessageDialog(PoolPanel.this, new JScrollPane(text), "导入结果",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception error) { Ui.error(PoolPanel.this, error); }
                finally { afterward.run(); }
            }
        }.execute();
    }

    private void importArchives(List<Path> archives) {
        if (archives.isEmpty()) return;
        if (busy) return;
        startProgress("准备 ZIP 发票扫描");
        ZipInvoiceImportService importer = new ZipInvoiceImportService(app);
        new SwingWorker<ZipInvoiceImportService.Report, String>() {
            @Override protected ZipInvoiceImportService.Report doInBackground() throws Exception {
                return importer.importArchives(archives, this::publish);
            }
            @Override protected void process(List<String> updates) {
                progress.setText(updates.getLast());
            }
            @Override protected void done() {
                stopProgress();
                try {
                    ZipInvoiceImportService.Report report = get();
                    window.refreshAll();
                    progress.setText("ZIP 扫描完成 · " + report.importedCount() + " 份发票已入池");
                    new ZipImportDialog(window, app, importer, report).showDialog();
                } catch (Exception error) {
                    window.refreshAll();
                    progress.setText("ZIP 处理失败，请查看错误详情");
                    Ui.error(PoolPanel.this, error);
                }
            }
        }.execute();
    }

    private List<Invoice> selectedInvoices() {
        List<Invoice> selected = new ArrayList<>();
        for (int row : table.getSelectedRows()) if (row >= 0 && row < shown.size()) selected.add(shown.get(row));
        return selected;
    }

    private void maybeMenu(MouseEvent event) {
        if (!event.isPopupTrigger()) return;
        int row = table.rowAtPoint(event.getPoint());
        if (row < 0) return;
        if (!table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
        menu(selectedInvoices()).show(table, event.getX(), event.getY());
    }

    private JPopupMenu menu(List<Invoice> selected) {
        Invoice invoice = selected.getFirst();
        boolean single = selected.size() == 1;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem assign = new JMenuItem(single ? "归属于" : "批量归属于（" + selected.size() + " 张）");
        JMenuItem unassign = new JMenuItem("取消归属");
        JMenuItem amountTotal = new JMenuItem("计算金额");
        JMenuItem edit = new JMenuItem("查看/编辑");
        JMenuItem aiReview = new JMenuItem("AI 识别发票");
        JMenuItem ocrReview = new JMenuItem("OCR 识别发票");
        JMenuItem delete = new JMenuItem("删除发票");
        JMenuItem open = new JMenuItem("打开原件");
        assign.addActionListener(event -> ownerPicker(selected));
        unassign.setEnabled(single && invoice.ownerId != null);
        unassign.addActionListener(event -> {
            try { app.unassign(invoice.id); window.refreshAll(); }
            catch (Exception error) { Ui.error(this, error); }
        });
        amountTotal.setEnabled(!single);
        amountTotal.addActionListener(event -> showAmountTotal(selected));
        edit.setEnabled(single);
        edit.addActionListener(event -> edit(invoice));
        aiReview.setEnabled(single && invoice.reimbursedAt == null);
        aiReview.addActionListener(event -> reRecognize(invoice, true));
        ocrReview.setEnabled(single && invoice.reimbursedAt == null);
        ocrReview.addActionListener(event -> reRecognize(invoice, false));
        delete.setEnabled(single && invoice.reimbursedAt == null);
        if (invoice.reimbursedAt != null) delete.setToolTipText("已报销发票不可删除，请先撤销报销");
        delete.addActionListener(event -> deleteInvoice(invoice));
        open.setEnabled(single);
        open.addActionListener(event -> {
            try { java.awt.Desktop.getDesktop().open(app.original(invoice).toFile()); }
            catch (Exception error) { Ui.error(this, error); }
        });
        menu.add(assign);
        menu.add(unassign);
        menu.add(amountTotal);
        menu.addSeparator();
        menu.add(edit);
        menu.add(aiReview);
        menu.add(ocrReview);
        menu.add(open);
        menu.addSeparator();
        menu.add(delete);
        return menu;
    }

    private void showAmountTotal(List<Invoice> selected) {
        try {
            long cents = 0;
            int missing = 0;
            for (Invoice invoice : selected) {
                if (invoice.amountCents == null) missing++;
                else cents = Math.addExact(cents, invoice.amountCents);
            }
            String message = "已选择 " + selected.size() + " 张发票\n金额合计：" + Ui.money(cents);
            if (missing > 0) message += "\n其中 " + missing + " 张金额未识别，未计入合计。";
            JOptionPane.showMessageDialog(this, message, "计算金额", JOptionPane.INFORMATION_MESSAGE);
        } catch (ArithmeticException error) {
            Ui.error(this, new IllegalArgumentException("所选发票金额合计超出可计算范围", error));
        }
    }

    private void edit(Invoice invoice) {
        InvoiceEditor.show(window, app, invoice, window::refreshAll);
    }

    private void reRecognize(Invoice invoice, boolean ai) {
        if (busy) return;
        if (ai && !app.settings().current().configured()) {
            window.showSettings();
            return;
        }
        startProgress(ai ? "准备 AI 识别" : "正在 OCR 识别");
        new SwingWorker<Invoice, String>() {
            @Override protected Invoice doInBackground() throws Exception {
                return ai ? app.reRecognize(invoice.id, this::publish) : app.reRecognizeOcr(invoice.id);
            }
            @Override protected void process(List<String> phases) {
                progress.setText(phases.getLast() + " · " + invoice.originalName);
            }
            @Override protected void done() {
                stopProgress();
                try {
                    get();
                    window.refreshAll();
                    progress.setIcon(AppIcons.of(AppIcons.Kind.CHECK, new Color(0x2E7664)));
                    progress.setText((ai ? "AI" : "OCR") + " 识别完成 · 请核对字段 · " + invoice.originalName);
                } catch (Exception error) {
                    showError(error);
                }
            }
        }.execute();
    }

    private void startProgress(String message) {
        fullError = null;
        statusDetails.setVisible(false);
        busy = true;
        frame = 0;
        progress.setText(message);
        progress.setIcon(AppIcons.spinner(new Color(0x236C73), frame++));
        spinner.start();
    }

    private void stopProgress() {
        busy = false;
        spinner.stop();
        progress.setIcon(null);
    }

    private void showError(Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        java.io.StringWriter details = new java.io.StringWriter();
        cause.printStackTrace(new java.io.PrintWriter(details));
        fullError = details.toString();
        String summary = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        summary = summary.replace('\n', ' ').replace('\r', ' ');
        if (summary.length() > 90) summary = summary.substring(0, 90) + "…";
        progress.setText("识别失败，原记录未修改");
        statusDetails.setText(summary + "（查看详情）");
        statusDetails.setIcon(AppIcons.of(AppIcons.Kind.WARNING, new Color(0x9A6324)));
        statusDetails.setForeground(new Color(0x9A6324));
        statusDetails.setVisible(true);
    }

    private void showFullError() {
        if (fullError == null) return;
        JTextArea details = new JTextArea(fullError, 16, 72);
        details.setEditable(false);
        JOptionPane.showMessageDialog(this, new JScrollPane(details), "识别错误详情", JOptionPane.WARNING_MESSAGE);
    }

    private void deleteInvoice(Invoice invoice) {
        Person assigned = invoice.ownerId == null ? null : people.get(invoice.ownerId);
        String owner = assigned == null ? null : assigned.name();
        String message = owner == null ? "将这张发票从发票池移除？"
                : "将这张发票从发票池移除？这会取消其对“" + owner + "”的归属。";
        if (JOptionPane.showConfirmDialog(this, message + "\n原件会保留在工作目录中。",
                "确认删除发票", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            app.deleteInvoice(invoice.id);
            window.refreshAll();
        } catch (Exception error) { Ui.error(this, error); }
    }

    private void ownerPicker(List<Invoice> selected) {
        try {
            JDialog dialog = new JDialog(window, selected.size() == 1
                    ? "归属于 · " + selected.getFirst().originalName : "批量归属 · " + selected.size() + " 张发票", true);
            dialog.setLayout(new BorderLayout(8, 8));
            JPanel sections = new JPanel(new BorderLayout(8, 8));
            List<Person> recent = app.recentOwners();
            JPanel recentPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            for (Person person : recent) {
                recentPanel.add(ownerButton(dialog, selected, person));
            }
            JPanel top = new JPanel(new BorderLayout());
            top.add(new JLabel("最近选择（最多4人）"), BorderLayout.NORTH);
            top.add(recentPanel, BorderLayout.CENTER);
            sections.add(top, BorderLayout.NORTH);
            JPanel everyone = new JPanel(new GridLayout(0, 4, 8, 8));
            for (Person person : app.people()) everyone.add(ownerButton(dialog, selected, person));
            JPanel all = new JPanel(new BorderLayout());
            all.add(new JLabel("全部人员"), BorderLayout.NORTH);
            all.add(everyone, BorderLayout.CENTER);
            sections.add(all, BorderLayout.CENTER);
            dialog.add(new JScrollPane(sections), BorderLayout.CENTER);
            dialog.setSize(650, 460);
            dialog.setLocationRelativeTo(window);
            dialog.setVisible(true);
        } catch (Exception error) { Ui.error(this, error); }
    }

    private JButton ownerButton(JDialog dialog, List<Invoice> selected, Person person) {
        JButton button = new JButton(person.name(), Ui.avatar(app, person, 32));
        button.setHorizontalTextPosition(JButton.CENTER);
        button.setVerticalTextPosition(JButton.BOTTOM);
        button.setPreferredSize(new java.awt.Dimension(130, 70));
        button.addActionListener(event -> {
            try {
                YearMonth month = selected.getFirst().reimbursementMonth;
                YearMonth initialMonth = month;
                boolean sameMonth = initialMonth != null && selected.stream()
                        .allMatch(invoice -> initialMonth.equals(invoice.reimbursementMonth));
                if (!sameMonth || selected.size() > 1) {
                    String chosen = JOptionPane.showInputDialog(dialog,
                            "为所选 " + selected.size() + " 张发票选择同一个报销月份（YYYY-MM）",
                            sameMonth ? month.toString() : YearMonth.now(java.time.ZoneId.of("Asia/Shanghai")).toString());
                    if (chosen == null) return;
                    month = YearMonth.parse(chosen.trim());
                }
                app.assignMany(selected.stream().map(invoice -> invoice.id).toList(), person.id(), month);
                dialog.dispose();
                window.refreshAll();
            } catch (Exception error) { Ui.error(dialog, error); }
        });
        return button;
    }
}
