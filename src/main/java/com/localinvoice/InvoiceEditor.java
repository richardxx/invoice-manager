package com.localinvoice;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

public final class InvoiceEditor {
    private InvoiceEditor() { }

    public static void show(JFrame parent, AppService app, Invoice source, Runnable onSave) {
        try {
            Invoice invoice = app.invoice(source.id);
            if (invoice == null) throw new IllegalArgumentException("发票不存在");
            JDialog dialog = new JDialog(parent, "查看/编辑发票 · " + invoice.originalName, true);
            dialog.setLayout(new BorderLayout(8, 8));
            JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
            form.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 14, 14, 14));
            JTextField issuer = field(invoice.issuer);
            JTextField buyer = field(invoice.buyer);
            JTextField traveler = field(invoice.traveler);
            JTextField travelDate = field(invoice.travelDate);
            JTextField number = field(invoice.invoiceNumber);
            JTextField issueDate = field(invoice.issueDate);
            JTextField amount = new JTextField(invoice.amountCents == null ? ""
                    : BigDecimal.valueOf(invoice.amountCents, 2).toPlainString());
            JComboBox<Category> category = new JComboBox<>(Category.values());
            category.setSelectedItem(invoice.category == null ? Category.OTHER : invoice.category);
            JTextField month = field(invoice.reimbursementMonth);
            form.add(new JLabel("原始文件")); form.add(new JLabel(invoice.originalName));
            form.add(new JLabel("识别状态")); form.add(new JLabel(invoice.reviewStatus.label()
                    + (invoice.recognitionSource == null ? "" : " · " + invoice.recognitionSource)
                    + (invoice.duplicateOf == null ? "" : " · 疑似重复，请核对")));
            form.add(new JLabel("开票方 / 销售方 *")); form.add(issuer);
            form.add(new JLabel("购买方")); form.add(buyer);
            form.add(new JLabel("旅客 / 乘车人")); form.add(traveler);
            form.add(new JLabel("乘车日期 YYYY-MM-DD")); form.add(travelDate);
            form.add(new JLabel("发票号码")); form.add(number);
            form.add(new JLabel("开票日期 YYYY-MM-DD *")); form.add(issueDate);
            form.add(new JLabel("价税合计（元）*")); form.add(amount);
            form.add(new JLabel("消费类型 *")); form.add(category);
            form.add(new JLabel("报销月份 YYYY-MM")); form.add(month);
            JCheckBox confirmed = new JCheckBox("我已核对原件，确认以上信息可用于报销统计");
            confirmed.setSelected(invoice.reviewStatus == ReviewStatus.READY);
            JPanel center = new JPanel(new BorderLayout());
            center.add(form, BorderLayout.CENTER);
            center.add(confirmed, BorderLayout.SOUTH);
            dialog.add(new JScrollPane(center), BorderLayout.CENTER);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton original = new JButton("打开原件");
            JButton cancel = new JButton("取消");
            JButton save = new JButton("保存");
            original.addActionListener(event -> {
                try { java.awt.Desktop.getDesktop().open(app.original(invoice).toFile()); }
                catch (Exception error) { Ui.error(dialog, error); }
            });
            cancel.addActionListener(event -> dialog.dispose());
            save.addActionListener(event -> {
                try {
                    invoice.issuer = optional(issuer.getText());
                    invoice.buyer = optional(buyer.getText());
                    invoice.traveler = optional(traveler.getText());
                    invoice.travelDate = parseDate(travelDate.getText());
                    invoice.invoiceNumber = optional(number.getText());
                    invoice.issueDate = parseDate(issueDate.getText());
                    invoice.amountCents = parseCents(amount.getText());
                    invoice.category = (Category) category.getSelectedItem();
                    invoice.reimbursementMonth = month.getText().isBlank() ? null : YearMonth.parse(month.getText().trim());
                    if (invoice.duplicateOf != null && confirmed.isSelected()) {
                        String reason = javax.swing.JOptionPane.showInputDialog(dialog,
                                "这张发票与已有记录疑似重复。请填写确认保留的原因：", "核对说明",
                                javax.swing.JOptionPane.QUESTION_MESSAGE);
                        if (reason == null) return;
                        invoice.manuallyEdited = reason.trim();
                    } else {
                        invoice.manuallyEdited = "已人工编辑";
                    }
                    app.saveInvoice(invoice, confirmed.isSelected());
                    dialog.dispose();
                    onSave.run();
                } catch (Exception error) { Ui.error(dialog, error); }
            });
            actions.add(original);
            actions.add(cancel);
            actions.add(save);
            dialog.add(actions, BorderLayout.SOUTH);
            dialog.setSize(680, 650);
            dialog.setLocationRelativeTo(parent);
            dialog.setVisible(true);
        } catch (Exception error) { Ui.error(parent, error); }
    }

    private static JTextField field(Object value) { return new JTextField(value == null ? "" : value.toString(), 22); }
    private static String optional(String text) { return text == null || text.isBlank() ? null : text.trim(); }
    private static LocalDate parseDate(String text) { return text.isBlank() ? null : LocalDate.parse(text.trim()); }
    private static Long parseCents(String text) {
        if (text.isBlank()) return null;
        BigDecimal decimal = new BigDecimal(text.trim()).setScale(2, RoundingMode.UNNECESSARY);
        return decimal.movePointRight(2).longValueExact();
    }
}
