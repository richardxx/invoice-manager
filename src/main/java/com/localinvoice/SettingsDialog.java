package com.localinvoice;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;

public final class SettingsDialog {
    private SettingsDialog() { }

    public static void show(JFrame owner, SettingsService service, Path currentRoot,
            WorkDirectoryService work, boolean canChangeDirectory) {
        AiSettings existing = service.current();
        JDialog dialog = new JDialog(owner, "选项", true);
        dialog.setLayout(new BorderLayout(12, 12));
        JTabbedPane tabs = new JTabbedPane();
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(18, 18, 10, 18));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        JTextField baseUrl = new JTextField(existing.baseUrl().isBlank()
                ? "https://api.openai.com/v1" : existing.baseUrl(), 32);
        JTextField model = new JTextField(existing.model(), 32);
        JPasswordField apiKey = new JPasswordField(existing.apiKey(), 32);
        row(form, c, 0, "Base URL", baseUrl);
        row(form, c, 1, "模型名称", model);
        row(form, c, 2, "API Key", apiKey);
        JLabel note = new JLabel("导入时先用 PDF_TEXT，再用 OCR；仅手动点击 AI 识别时发送发票图片。");
        c.gridx = 0; c.gridy = 3; c.gridwidth = 2;
        form.add(note, c);
        JLabel storage = new JLabel(System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "API Key 使用当前 Windows 账户的 DPAPI 加密保存。"
                : "此系统上 API Key 仅在本次运行期间保存。");
        c.gridy = 4;
        form.add(storage, c);
        tabs.addTab("AI 识别", form);

        JPanel system = new JPanel(new GridBagLayout());
        system.setBorder(BorderFactory.createEmptyBorder(18, 18, 10, 18));
        GridBagConstraints s = new GridBagConstraints();
        s.insets = new Insets(6, 5, 6, 5);
        s.fill = GridBagConstraints.HORIZONTAL;
        s.weightx = 1;
        JTextField workDirectory = new JTextField(currentRoot.toAbsolutePath().normalize().toString(), 34);
        workDirectory.setEditable(canChangeDirectory);
        JButton browse = new JButton("选择文件夹...");
        browse.setEnabled(canChangeDirectory);
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(workDirectory.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION)
                workDirectory.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        s.gridx = 0; s.gridy = 0; s.weightx = 0;
        system.add(new JLabel("工作目录"), s);
        s.gridx = 1; s.weightx = 1;
        system.add(workDirectory, s);
        s.gridx = 2; s.weightx = 0;
        system.add(browse, s);
        s.gridx = 0; s.gridy = 1; s.gridwidth = 3;
        system.add(new JLabel("发票原件、人员、报销记录和设置保存在此目录。升级软件不会清除该目录。"), s);
        s.gridy = 2;
        system.add(new JLabel(canChangeDirectory
                ? "更改目录时会复制并校验现有数据；成功后自动重启，原目录保留。"
                : "当前工作目录由启动参数指定，无法在选项中更改。"), s);
        s.gridy = 3; s.weighty = 1; s.fill = GridBagConstraints.BOTH;
        system.add(new JPanel(), s);
        tabs.addTab("系统", system);
        dialog.add(tabs, BorderLayout.CENTER);
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        JButton cancel = new JButton("取消");
        JButton save = new JButton("保存选项");
        cancel.addActionListener(event -> dialog.dispose());
        save.addActionListener(event -> {
            try {
                Path target = Path.of(workDirectory.getText().trim()).toAbsolutePath().normalize();
                boolean move = canChangeDirectory && !target.equals(currentRoot.toAbsolutePath().normalize());
                if (move) {
                    target = work.validateDestination(target);
                    String message = "现有数据将复制到新工作目录并逐个校验：\n" + target
                            + "\n\n原工作目录会保留。迁移成功后软件自动重启。确定继续吗？";
                    if (JOptionPane.showConfirmDialog(dialog, message, "迁移工作目录",
                            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
                }
                service.save(new AiSettings(baseUrl.getText(), model.getText(),
                        new String(apiKey.getPassword())));
                dialog.dispose();
                if (move) Main.restartForMigration(target);
            } catch (Exception error) { Ui.error(dialog, error); }
        });
        actions.add(cancel);
        actions.add(save);
        dialog.add(actions, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setMinimumSize(new java.awt.Dimension(720, 380));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private static void row(JPanel form, GridBagConstraints c, int row, String label, java.awt.Component field) {
        c.gridy = row; c.gridwidth = 1; c.weightx = 0; c.gridx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        form.add(field, c);
    }
}
