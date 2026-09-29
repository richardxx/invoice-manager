package com.localinvoice;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;

final class GuideDialog extends JDialog {
    private record Step(String tab, String title, String description) { }

    private static final Step[] STEPS = {
            new Step("1 人员", "先登记报销人", "在“人员管理”添加姓名、预设头像及收款资料。固定的“公司”账户无需填写收款资料，也不会进入银行 Excel。"),
            new Step("2 入池", "把发票放进池里", "在“发票池”点击“添加发票”，或直接拖入 PDF、ZIP。ZIP 会递归查找 PDF，并让你复核遗漏文件。"),
            new Step("3 核对", "核对识别信息", "检查开票方、金额、类型和开票日期。需要时可右键重新进行 OCR 识别，或在设置模型后手动进行 AI 识别。"),
            new Step("4 归属", "选择人员和报销月", "右键发票选择“归属于”，可以一次选中多张。指定报销人和报销月份；需要修改时可取消归属。"),
            new Step("5 报销", "完成报销并导出", "在“报销管理”查看月度矩阵，双击金额查看明细。核对后点击“完成报销”，再导出发票包或个人银行 Excel。")
    };

    GuideDialog(MainWindow owner) {
        super(owner, "使用方法 · 五步完成报销", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel body = new JPanel(new BorderLayout(12, 12));
        body.setBackground(RetroArt.PAPER);
        body.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createRaisedBevelBorder(),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        JLabel header = new JLabel("  本地发票管理  /  使用说明");
        header.setOpaque(true);
        header.setBackground(RetroArt.BLUE);
        header.setForeground(Color.WHITE);
        header.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 18));
        body.add(header, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        for (int i = 0; i < STEPS.length; i++) tabs.addTab(STEPS[i].tab(), stepPage(i));
        body.add(tabs, BorderLayout.CENTER);
        JButton close = new JButton("关闭说明");
        close.addActionListener(event -> dispose());
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(RetroArt.PAPER);
        bottom.add(new JLabel("提示：原始发票与资料保存在所选工作目录中。"), BorderLayout.WEST);
        bottom.add(close, BorderLayout.EAST);
        body.add(bottom, BorderLayout.SOUTH);
        setContentPane(body);
        setSize(840, 610);
        setLocationRelativeTo(owner);
    }

    private static JPanel stepPage(int index) {
        Step step = STEPS[index];
        JPanel page = new JPanel(new BorderLayout(10, 10));
        page.setBackground(RetroArt.PAPER);
        page.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JLabel title = new JLabel(step.title());
        title.setForeground(RetroArt.BLUE);
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 20));
        page.add(title, BorderLayout.NORTH);
        page.add(new MiniScreen(index), BorderLayout.CENTER);
        JTextArea description = new JTextArea(step.description());
        description.setEditable(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setOpaque(false);
        description.setForeground(RetroArt.INK);
        description.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 14));
        description.setBorder(BorderFactory.createEmptyBorder(5, 4, 5, 4));
        description.setPreferredSize(new Dimension(0, 72));
        page.add(description, BorderLayout.SOUTH);
        return page;
    }

    /** Small drawings of the real workflow, rather than screenshots of private invoice data. */
    private static final class MiniScreen extends JPanel {
        private static final Color TEAL = new Color(0x3C8585);
        private static final Color CORAL = new Color(0xD97762);
        private final int step;

        MiniScreen(int step) {
            this.step = step;
            setBackground(new Color(0xDFD8C5));
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLoweredBevelBorder(),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double scale = Math.min((getWidth() - 20) / 720.0, (getHeight() - 20) / 320.0);
            g.translate((getWidth() - 720 * scale) / 2, (getHeight() - 320 * scale) / 2);
            g.scale(scale, scale);
            g.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 15));
            g.setColor(new Color(0x465365));
            g.fillRoundRect(0, 0, 720, 320, 12, 12);
            g.setColor(new Color(0xEDE9DC));
            g.fillRect(7, 29, 706, 284);
            g.setColor(RetroArt.BLUE);
            g.fillRect(7, 7, 706, 27);
            g.setColor(Color.WHITE);
            g.drawString("本地发票管理", 22, 27);
            g.setColor(new Color(0xD5DCCF));
            g.fillRect(7, 34, 130, 279);
            String[] menu = {"报销管理", "发票池", "人员管理"};
            for (int i = 0; i < menu.length; i++) {
                if ((step == 0 && i == 2) || (step > 0 && step < 4 && i == 1) || (step == 4 && i == 0)) {
                    g.setColor(TEAL);
                    g.fillRoundRect(16, 69 + i * 45, 112, 32, 7, 7);
                    g.setColor(Color.WHITE);
                } else g.setColor(RetroArt.INK);
                g.drawString(menu[i], 31, 91 + i * 45);
            }
            g.setColor(RetroArt.INK);
            switch (step) {
                case 0 -> drawPeople(g);
                case 1 -> drawImport(g);
                case 2 -> drawReview(g);
                case 3 -> drawAssign(g);
                default -> drawExport(g);
            }
            g.dispose();
        }

        private void drawPeople(Graphics2D g) {
            g.drawString("人员管理", 163, 69);
            box(g, 161, 82, 520, 43, "头像          姓名                   已完成报销总额");
            box(g, 161, 130, 520, 48, "  ●            小王                         ¥328.00");
            box(g, 161, 183, 520, 48, "  ●            小李                         ¥760.00");
            box(g, 400, 246, 180, 41, "＋ 添加人员");
        }

        private void drawImport(Graphics2D g) {
            g.drawString("发票池", 163, 69);
            box(g, 165, 108, 132, 140, " PDF");
            box(g, 315, 108, 132, 140, " ZIP");
            g.setColor(CORAL);
            g.setStroke(new BasicStroke(6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(468, 179, 536, 179);
            g.drawLine(525, 168, 538, 179);
            g.drawLine(525, 190, 538, 179);
            box(g, 551, 110, 116, 138, "发票池");
        }

        private void drawReview(Graphics2D g) {
            g.drawString("核对发票信息", 163, 69);
            box(g, 162, 83, 520, 40, "文件名             开票方             金额       类型");
            box(g, 162, 126, 520, 48, "餐饮.pdf          餐厅             ¥231.00     餐饮");
            box(g, 162, 178, 520, 48, "车票.pdf          铁路             ¥177.53     交通");
            box(g, 362, 244, 180, 40, "重新识别 / 编辑");
        }

        private void drawAssign(Graphics2D g) {
            g.drawString("选择归属人员", 163, 69);
            box(g, 166, 88, 500, 170, "");
            for (int i = 0; i < 4; i++) {
                int x = 210 + i * 112;
                g.setColor(i == 1 ? CORAL : TEAL);
                g.fillOval(x, 124, 55, 55);
                g.setColor(Color.WHITE);
                g.drawString(i == 1 ? "李" : "人", x + 19, 159);
                g.setColor(RetroArt.INK);
                g.drawString(i == 1 ? "小李" : "人员", x + 7, 206);
            }
            g.drawString("报销月份：2026-09", 322, 282);
        }

        private void drawExport(Graphics2D g) {
            g.drawString("2026-09  报销管理", 163, 69);
            box(g, 160, 81, 515, 42, "人员        交通       餐饮       住宿       总额");
            box(g, 160, 127, 515, 50, "小李      ¥177.53    ¥231.00   ¥0.00     ¥408.53");
            box(g, 160, 197, 154, 42, "✓ 完成报销");
            box(g, 324, 197, 154, 42, "导出发票包");
            box(g, 488, 197, 181, 42, "导出银行 Excel");
        }

        private void box(Graphics2D g, int x, int y, int width, int height, String label) {
            g.setColor(new Color(0xFAF8EE));
            g.fillRoundRect(x, y, width, height, 8, 8);
            g.setColor(new Color(0x789193));
            g.setStroke(new BasicStroke(2));
            g.drawRoundRect(x, y, width, height, 8, 8);
            g.setColor(RetroArt.INK);
            g.drawString(label, x + 12, y + height / 2 + 5);
        }
    }
}
