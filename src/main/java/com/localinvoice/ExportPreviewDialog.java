package com.localinvoice;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

public final class ExportPreviewDialog {
    private ExportPreviewDialog() { }

    public static List<ExportService.ExportItem> show(MainWindow owner, YearMonth month,
                                                       List<ExportService.ExportItem> preview) {
        AtomicReference<List<ExportService.ExportItem>> approved = new AtomicReference<>();
        JDialog dialog = new JDialog(owner, "导出发票包 · " + month, true);
        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel heading = new JPanel(new GridLayout(2, 1, 0, 6));
        heading.add(new JLabel("请核对本次导出的发票"));
        heading.add(new JLabel("单击“导出文件名”可直接修改；文件名须唯一，并以 .pdf 结尾。"));
        content.add(heading, BorderLayout.NORTH);

        DefaultTableModel model = new DefaultTableModel(new String[]{"报销人", "报销金额", "报销类型", "导出文件名"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return column == 3; }
        };
        long total = 0;
        for (ExportService.ExportItem item : preview) {
            model.addRow(new Object[]{item.ownerName(), Ui.money(item.amountCents()),
                    item.category().label(), item.fileName()});
            total = Math.addExact(total, item.amountCents());
        }
        JTable table = new JTable(model);
        table.setRowHeight(32);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        DefaultCellEditor filenameEditor = new DefaultCellEditor(new JTextField());
        filenameEditor.setClickCountToStart(1);
        table.getColumnModel().getColumn(3).setCellEditor(filenameEditor);
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(440);
        content.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(new JLabel("共 " + preview.size() + " 张 · 合计 " + Ui.money(total) + "    "));
        JButton confirm = new JButton("确认");
        JButton cancel = new JButton("取消");
        confirm.addActionListener(event -> {
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return;
            List<ExportService.ExportItem> edited = new ArrayList<>();
            for (int row = 0; row < preview.size(); row++)
                edited.add(preview.get(row).withFileName((String) model.getValueAt(row, 3)));
            try {
                ExportService.validateFileNames(edited);
                approved.set(List.copyOf(edited));
                dialog.dispose();
            } catch (IllegalArgumentException error) {
                Ui.error(dialog, error);
            }
        });
        cancel.addActionListener(event -> dialog.dispose());
        actions.add(confirm);
        actions.add(cancel);
        content.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setSize(860, 480);
        dialog.setMinimumSize(new java.awt.Dimension(660, 330));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return approved.get();
    }
}
