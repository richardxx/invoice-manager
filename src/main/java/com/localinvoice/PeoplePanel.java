package com.localinvoice;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Dimension;
import java.util.Map;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

public final class PeoplePanel extends JPanel {
    private final MainWindow window;
    private final AppService app;
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"人员", "已完成报销总额"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);

    public PeoplePanel(MainWindow window, AppService app) {
        super(new BorderLayout(8, 8));
        this.window = window;
        this.app = app;
        setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 18, 18, 18));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton add = new JButton("添加人员");
        JButton edit = new JButton("编辑");
        JButton delete = new JButton("删除");
        add.addActionListener(event -> editPerson(null));
        edit.addActionListener(event -> editPerson(selectedPerson()));
        delete.addActionListener(event -> deletePerson());
        top.add(add);
        top.add(edit);
        top.add(delete);
        add(top, BorderLayout.NORTH);
        table.setRowHeight(52);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(360);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable source, Object value, boolean selected,
                    boolean focused, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(source, value, selected, focused, row, column);
                Person person = (Person) value;
                label.setText(person.name() + (person.company() ? "  · 公司" : ""));
                label.setIcon(Ui.avatar(app, person, 36));
                label.setIconTextGap(14);
                return label;
            }
        });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(event)
                        || table.columnAtPoint(event.getPoint()) != 0) return;
                int row = table.rowAtPoint(event.getPoint());
                if (row < 0) return;
                table.setRowSelectionInterval(row, row);
                editPerson(selectedPerson());
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    public void refresh() throws Exception {
        Person selected = selectedPerson();
        String selectedId = selected == null ? null : selected.id();
        Map<String, Long> totals = app.completedTotals();
        model.setRowCount(0);
        for (Person person : app.people()) {
            model.addRow(new Object[]{person, Ui.money(totals.getOrDefault(person.id(), 0L))});
            if (person.id().equals(selectedId)) table.setRowSelectionInterval(model.getRowCount() - 1,
                    model.getRowCount() - 1);
        }
    }

    private Person selectedPerson() {
        int row = table.getSelectedRow();
        return row < 0 ? null : (Person) model.getValueAt(table.convertRowIndexToModel(row), 0);
    }

    private void editPerson(Person person) {
        if (person != null && person.company()) {
            JOptionPane.showMessageDialog(this, "公司账户无需填写收款资料，也不会纳入银行 Excel 导出。",
                    "公司账户", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        PaymentDetails existing = person == null ? PaymentDetails.empty() : person.payment();
        JTextField name = new JTextField(person == null ? "" : person.name(), 18);
        JComboBox<AvatarPresets.Option> avatars = new JComboBox<>(AvatarPresets.options().toArray(new AvatarPresets.Option[0]));
        avatars.setMaximumRowCount(8);
        avatars.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> source, Object value, int index,
                    boolean selected, boolean focused) {
                JLabel label = (JLabel) super.getListCellRendererComponent(source, value, index, selected, focused);
                if (value instanceof AvatarPresets.Option option) {
                    label.setText(option.label());
                    label.setIcon(AvatarPresets.icon(option.id(), 30));
                    label.setIconTextGap(10);
                }
                return label;
            }
        });
        if (person != null) {
            for (AvatarPresets.Option option : AvatarPresets.options()) {
                if (option.id().equals(person.avatarPath())) avatars.setSelectedItem(option);
            }
        }
        JTextField accountNumber = new JTextField(existing.accountNumber(), 24);
        JTextField accountName = new JTextField(existing.accountName(), 24);
        JComboBox<PaymentDetails.BankType> bankType = new JComboBox<>(PaymentDetails.BankType.values());
        if (existing.bankType() != null) bankType.setSelectedItem(existing.bankType());
        else bankType.setSelectedItem(null);
        JTextField cnaps = new JTextField(existing.cnapsNumber(), 24);
        JComboBox<String> identityType = new JComboBox<>(PaymentDetails.IDENTITY_TYPES.toArray(String[]::new));
        if (PaymentDetails.IDENTITY_TYPES.contains(existing.identityType()))
            identityType.setSelectedItem(existing.identityType());
        else if (!existing.identityType().isBlank()) identityType.setSelectedItem(null);
        JTextField identityNumber = new JTextField(existing.identityNumber(), 24);
        JPanel form = new JPanel(new java.awt.GridLayout(0, 2, 12, 12));
        form.setBorder(javax.swing.BorderFactory.createEmptyBorder(20, 24, 20, 24));
        form.add(new JLabel("姓名")); form.add(name);
        form.add(new JLabel("预设头像")); form.add(avatars);
        form.add(new JLabel("卡号/账号")); form.add(accountNumber);
        form.add(new JLabel("户名")); form.add(accountName);
        form.add(new JLabel("收款行类型")); form.add(bankType);
        form.add(new JLabel("收款行CNAPS号")); form.add(cnaps);
        form.add(new JLabel("证件类型")); form.add(identityType);
        form.add(new JLabel("证件号码")); form.add(identityNumber);
        if (!existing.identityType().isBlank() && !PaymentDetails.IDENTITY_TYPES.contains(existing.identityType())) {
            form.add(new JLabel("原证件类型"));
            form.add(new JLabel(existing.identityType() + "（请重新选择）"));
        }
        JScrollPane scroll = new JScrollPane(form);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        JDialog dialog = new JDialog(window, person == null ? "添加人员" : "编辑人员", true);
        dialog.setLayout(new BorderLayout());
        dialog.add(scroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 12));
        JButton save = new JButton("保存");
        JButton cancel = new JButton("取消");
        actions.add(save);
        actions.add(cancel);
        dialog.add(actions, BorderLayout.SOUTH);
        cancel.addActionListener(event -> dialog.dispose());
        save.addActionListener(event -> {
            try {
                PaymentDetails payment = new PaymentDetails(accountNumber.getText(), accountName.getText(),
                        (PaymentDetails.BankType) bankType.getSelectedItem(), cnaps.getText(),
                        (String) identityType.getSelectedItem(), identityNumber.getText());
                String avatar = ((AvatarPresets.Option) avatars.getSelectedItem()).id();
                if (person == null) app.addPerson(name.getText(), avatar, payment);
                else app.updatePerson(person, name.getText(), avatar, payment);
                window.refreshAll();
                dialog.dispose();
            } catch (Exception error) { Ui.error(dialog, error); }
        });
        dialog.getRootPane().setDefaultButton(save);
        dialog.setMinimumSize(new Dimension(700, 540));
        dialog.setSize(820, 640);
        dialog.setLocationRelativeTo(window);
        dialog.setVisible(true);
    }

    private void deletePerson() {
        Person person = selectedPerson();
        if (person == null) return;
        if (person.company()) { JOptionPane.showMessageDialog(this, "公司不能删除"); return; }
        if (JOptionPane.showConfirmDialog(this, "删除人员“" + person.name() + "”？其发票必须先取消或转移归属。",
                "确认删除", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try { app.deletePerson(person.id()); window.refreshAll(); }
        catch (Exception error) { Ui.error(this, error); }
    }
}
