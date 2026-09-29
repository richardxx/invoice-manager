package com.localinvoice;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;

final class AboutDialog extends JDialog {
    record Scene(String image, String caption) { }

    static List<Scene> scenes() {
        return List.of(
                new Scene("about-1.png", "大家的报销问题，一张接着一张地涌来。"),
                new Scene("about-2.png", "发票越堆越高，我被表格和票据折磨得团团转。"),
                new Scene("about-3.png", "后来，我有了一个主意：请 Codex 一起帮忙。"),
                new Scene("about-4.png", "我们把入池、识别、归属和月度报销做进软件。"),
                new Scene("about-5.png", "乱糟糟的发票，终于能按人、按月整理清楚。"),
                new Scene("about-6.png", "从此，报销少一点折磨，生活多一点轻松。"));
    }

    private final CardLayout cards = new CardLayout();
    private final JPanel pictures = new JPanel(cards);
    private final JLabel caption = new JLabel("", SwingConstants.CENTER);
    private final JLabel counter = new JLabel("", SwingConstants.RIGHT);
    private Timer player;
    private int index;
    private int pictureClicks;

    AboutDialog(MainWindow owner) {
        super(owner, "关于", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel body = new JPanel(new BorderLayout(10, 10));
        body.setBackground(RetroArt.PAPER);
        body.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createRaisedBevelBorder(),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)));
        JLabel banner = new JLabel("  关于本地发票管理  /  ABOUT");
        banner.setOpaque(true);
        banner.setBackground(RetroArt.BLUE);
        banner.setForeground(Color.WHITE);
        banner.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 17));
        body.add(banner, BorderLayout.NORTH);
        List<Scene> scenes = scenes();
        BufferedImage first = null;
        for (int i = 0; i < scenes.size(); i++) {
            BufferedImage image = RetroArt.load(scenes.get(i).image());
            if (first == null) first = image;
            var panel = RetroArt.panel(image);
            panel.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    if (++pictureClicks == 3) {
                        pictureClicks = 0;
                        index = 0;
                        showScene();
                        player.restart();
                    }
                }
            });
            pictures.add(panel, Integer.toString(i));
        }
        int pictureHeight = 500;
        pictures.setPreferredSize(new Dimension(
                (int) Math.round(pictureHeight * (double) first.getWidth() / first.getHeight()), pictureHeight));
        body.add(pictures, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 6));
        footer.setBackground(RetroArt.PAPER);
        caption.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 15));
        caption.setForeground(RetroArt.INK);
        footer.add(caption, BorderLayout.CENTER);
        counter.setForeground(RetroArt.BLUE);
        footer.add(counter, BorderLayout.EAST);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(RetroArt.PAPER);
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x9EAAA9)));
        bottom.add(new JLabel("创作者：0x2frogs  ·  软件版本：" + AppVersion.VERSION), BorderLayout.WEST);
        JButton close = new JButton("关闭");
        close.addActionListener(event -> dispose());
        bottom.add(close, BorderLayout.EAST);
        footer.add(bottom, BorderLayout.SOUTH);
        body.add(footer, BorderLayout.SOUTH);
        setContentPane(body);
        setResizable(false);
        pack();
        // FlatLaf's title pane takes additional height after the dialog becomes visible on Windows.
        setSize(getWidth(), getHeight() + 20);
        setLocationRelativeTo(owner);
        player = new Timer(3000, event -> {
            if (index < scenes.size() - 1) {
                index++;
                showScene();
            } else player.stop();
        });
        showScene();
    }

    private void showScene() {
        cards.show(pictures, Integer.toString(index));
        caption.setText(scenes().get(index).caption());
        counter.setText((index + 1) + " / " + scenes().size());
    }

    @Override public void dispose() {
        if (player != null) player.stop();
        super.dispose();
    }
}
