package com.localinvoice;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JButton;
import javax.swing.ButtonGroup;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.JToggleButton;

public final class MainWindow extends JFrame {
    private final AppService app;
    private final WorkDirectoryService work;
    private final boolean rootOverridden;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final ReimbursementPanel reimbursement;
    private final PoolPanel pool;
    private final PeoplePanel people;
    private final JLabel pageTitle = new JLabel("报销管理");
    private final JLabel pageSubtitle = new JLabel("按月查看报销额度与发票明细");

    public MainWindow(AppService app) throws Exception { this(app, null, true); }

    public MainWindow(AppService app, WorkDirectoryService work, boolean rootOverridden) throws Exception {
        super("本地发票管理");
        this.app = app;
        this.work = work;
        this.rootOverridden = rootOverridden;
        this.reimbursement = new ReimbursementPanel(this, app);
        this.pool = new PoolPanel(this, app);
        this.people = new PeoplePanel(this, app);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1050, 650));
        setSize(1280, 800);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        Color sidebarColor = RetroArt.BLUE;
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(sidebarColor);
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 2, new Color(0x16324D)));
        sidebar.setPreferredSize(new Dimension(212, 0));
        JPanel nav = new JPanel();
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        nav.setBackground(sidebarColor);
        nav.setBorder(BorderFactory.createEmptyBorder(22, 12, 12, 12));
        JLabel brand = new JLabel("  INVOICE  /  发票管理", AppIcons.of(AppIcons.Kind.RECEIPT,
                new Color(0xFFE3A0)), JLabel.LEFT);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 14f));
        brand.setForeground(RetroArt.PAPER);
        brand.setAlignmentX(LEFT_ALIGNMENT);
        nav.add(brand);
        nav.add(Box.createVerticalStrut(36));
        JLabel section = new JLabel("工作区");
        section.setForeground(new Color(0xC8DDDB));
        section.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 0));
        section.setAlignmentX(LEFT_ALIGNMENT);
        nav.add(section);
        ButtonGroup group = new ButtonGroup();
        addNav(nav, group, "报销管理", "reimbursement", "按月查看报销额度与发票明细", AppIcons.Kind.RECEIPT, true);
        addNav(nav, group, "发票池", "pool", "导入、核对与归属发票", AppIcons.Kind.POOL, false);
        addNav(nav, group, "人员管理", "people", "维护报销人员与头像", AppIcons.Kind.PEOPLE, false);
        sidebar.add(nav, BorderLayout.NORTH);
        JButton settings = new JButton("选项", AppIcons.of(AppIcons.Kind.SETTINGS, RetroArt.PAPER));
        settings.setHorizontalAlignment(JButton.LEFT);
        settings.setIconTextGap(14);
        settings.setContentAreaFilled(false);
        settings.setBorderPainted(false);
        settings.setForeground(RetroArt.PAPER);
        settings.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 10));
        settings.addActionListener(event -> showSettings());
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(sidebarColor);
        footer.setBorder(BorderFactory.createEmptyBorder(8, 12, 14, 12));
        footer.add(settings, BorderLayout.CENTER);
        sidebar.add(footer, BorderLayout.SOUTH);
        add(sidebar, BorderLayout.WEST);
        content.add(reimbursement, "reimbursement");
        content.add(pool, "pool");
        content.add(people, "people");
        JPanel main = new JPanel(new BorderLayout());
        JPanel header = new JPanel(new java.awt.GridLayout(2, 1, 0, 2));
        header.setBackground(RetroArt.PAPER);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, new Color(0x9DB0A3)),
                BorderFactory.createEmptyBorder(16, 22, 16, 22)));
        pageTitle.setFont(pageTitle.getFont().deriveFont(Font.BOLD, 22f));
        pageTitle.setForeground(RetroArt.BLUE);
        pageSubtitle.setForeground(new Color(0x5A6967));
        header.add(pageTitle);
        header.add(pageSubtitle);
        main.add(header, BorderLayout.NORTH);
        main.add(content, BorderLayout.CENTER);
        add(main, BorderLayout.CENTER);
        cards.show(content, "reimbursement");
        setJMenuBar(menu());
        refreshAll();
    }

    public void refreshAll() {
        try {
            reimbursement.refresh();
            pool.refresh();
            people.refresh();
        } catch (Exception error) {
            Ui.error(this, error);
        }
    }

    private void addNav(JPanel nav, ButtonGroup group, String label, String card, String subtitle,
            AppIcons.Kind icon, boolean selected) {
        JToggleButton button = new JToggleButton(label, AppIcons.of(icon, RetroArt.PAPER)) {
            @Override protected void paintComponent(Graphics graphics) {
                if (isSelected()) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(0x3C8585));
                    g.fillRoundRect(0, 1, getWidth(), getHeight() - 2, 3, 3);
                    g.setColor(new Color(0xFFD08B));
                    g.fillRect(0, 7, 3, getHeight() - 14);
                    g.dispose();
                }
                super.paintComponent(graphics);
            }
        };
        button.setSelected(selected);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setForeground(RetroArt.PAPER);
        button.addItemListener(event -> button.setForeground(button.isSelected()
                ? Color.WHITE : RetroArt.PAPER));
        group.add(button);
        button.setHorizontalAlignment(JButton.LEFT);
        button.setIconTextGap(14);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        button.setAlignmentX(LEFT_ALIGNMENT);
        button.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 10));
        button.addActionListener(event -> {
            refreshAll();
            cards.show(content, card);
            pageTitle.setText(label);
            pageSubtitle.setText(subtitle);
        });
        nav.add(button);
        nav.add(Box.createVerticalStrut(5));
    }

    private JMenuBar menu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("数据");
        JMenuItem backup = new JMenuItem("备份数据...");
        JMenuItem restore = new JMenuItem("恢复备份...");
        backup.addActionListener(this::backup);
        restore.addActionListener(this::restore);
        file.add(backup);
        file.add(restore);
        bar.add(file);
        JMenu options = new JMenu("设置");
        JMenuItem settings = new JMenuItem("选项...");
        settings.addActionListener(event -> showSettings());
        options.add(settings);
        bar.add(options);
        JMenu help = new JMenu("帮助");
        JMenuItem guide = new JMenuItem("使用方法...");
        guide.addActionListener(event -> new GuideDialog(this).setVisible(true));
        JMenuItem about = new JMenuItem("关于...");
        about.addActionListener(event -> new AboutDialog(this).setVisible(true));
        help.add(guide);
        help.add(about);
        bar.add(help);
        return bar;
    }

    void showSettings() {
        SettingsDialog.show(this, app.settings(), app.root(), work, !rootOverridden);
    }

    private void backup(ActionEvent event) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(Path.of("发票管理备份.limbackup").toFile());
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        if (Files.exists(path) && JOptionPane.showConfirmDialog(this, "将覆盖已有备份文件，继续吗？", "确认覆盖",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception { return new BackupService(app).backup(path); }
            @Override protected void done() {
                try { JOptionPane.showMessageDialog(MainWindow.this, "备份完成，包含 " + get() + " 个附件。\n备份含敏感发票信息，请妥善保管。"); }
                catch (Exception error) { Ui.error(MainWindow.this, error); }
            }
        }.execute();
    }

    private void restore(ActionEvent event) {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        if (JOptionPane.showConfirmDialog(this, "恢复将替换当前所有人员和发票数据。请先备份当前数据。确定继续吗？",
                "确认替换数据", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { new BackupService(app).restore(path); return null; }
            @Override protected void done() {
                try { get(); refreshAll(); JOptionPane.showMessageDialog(MainWindow.this, "恢复完成"); }
                catch (Exception error) { Ui.error(MainWindow.this, error); }
            }
        }.execute();
    }
}
