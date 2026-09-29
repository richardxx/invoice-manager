package com.localinvoice;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;

final class SplashWindow extends JDialog {
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel status = new JLabel("准备启动...");

    SplashWindow() {
        super((java.awt.Frame) null, false);
        setUndecorated(true);
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setBackground(RetroArt.PAPER);
        body.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(RetroArt.INK, 2),
                BorderFactory.createCompoundBorder(BorderFactory.createRaisedBevelBorder(),
                        BorderFactory.createEmptyBorder(12, 12, 12, 12))));
        JLabel title = new JLabel("  本地发票管理  ·  InvoiceManage", SwingConstants.LEFT);
        title.setOpaque(true);
        title.setBackground(RetroArt.BLUE);
        title.setForeground(Color.WHITE);
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 18));
        title.setPreferredSize(new Dimension(0, 36));
        body.add(title, BorderLayout.NORTH);
        body.add(RetroArt.panel(RetroArt.load("splash.png")), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(0, 6));
        footer.setBackground(RetroArt.PAPER);
        JLabel promise = new JLabel("把发票收好，把报销时间还给自己。", SwingConstants.CENTER);
        promise.setForeground(RetroArt.INK);
        promise.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 16));
        footer.add(promise, BorderLayout.NORTH);
        status.setForeground(RetroArt.BLUE);
        status.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        footer.add(status, BorderLayout.CENTER);
        progress.setStringPainted(true);
        progress.setForeground(new Color(0x377D80));
        progress.setBackground(Color.WHITE);
        footer.add(progress, BorderLayout.SOUTH);
        body.add(footer, BorderLayout.SOUTH);
        setContentPane(body);
        setSize(700, 600);
        setLocationRelativeTo(null);
    }

    void update(int percent, String phase) {
        progress.setValue(percent);
        status.setText(phase);
    }
}
