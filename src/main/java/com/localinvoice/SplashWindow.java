package com.localinvoice;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import javax.swing.JDialog;
import javax.swing.JPanel;

final class SplashWindow extends JDialog {
    private static final int WINDOW_WIDTH = 800;
    private static final int WINDOW_HEIGHT = 560;

    private final SplashCanvas canvas;

    SplashWindow() {
        super((Frame) null, false);
        BufferedImage artwork = RetroArt.load("splash.png");
        canvas = new SplashCanvas(artwork);
        setUndecorated(true);
        setContentPane(canvas);
        setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        setLocationRelativeTo(null);
    }

    void update(int percent, String phase) {
        canvas.setProgress(percent, phase);
    }

    private static final class SplashCanvas extends JPanel {
        private static final int TEXT_X = 72;
        private static final int PROGRESS_X = 850;
        private static final int BAR_Y = 935;
        private static final int BAR_WIDTH = 460;
        private static final int BAR_HEIGHT = 9;
        private static final Font TITLE_FONT = new Font("Georgia", Font.BOLD | Font.ITALIC, 76);

        private final BufferedImage artwork;
        private int percent;
        private String phase = "准备启动...";

        private SplashCanvas(BufferedImage artwork) {
            this.artwork = artwork;
        }

        private void setProgress(int percent, String phase) {
            this.percent = Math.max(0, Math.min(100, percent));
            this.phase = phase == null ? "" : phase;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                double scale = getWidth() / (double) artwork.getWidth();
                g.drawImage(artwork, 0, 0, getWidth(), (int) Math.round(artwork.getHeight() * scale), null);
                g.scale(scale, scale);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                Shape title = TITLE_FONT.createGlyphVector(g.getFontRenderContext(), "Invoice Manager")
                        .getOutline(TEXT_X, 912);
                g.setColor(new Color(0x977653));
                g.fill(AffineTransform.getTranslateInstance(5, 5).createTransformedShape(title));
                g.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(0xE6BF7B));
                g.draw(title);
                g.setPaint(new GradientPaint(0, 850, new Color(0x397A99), 0, 920, new Color(0x194264)));
                g.fill(title);

                g.setColor(RetroArt.BLUE);
                g.setFont(new Font("Georgia", Font.PLAIN, 29));
                g.drawString("Version " + AppVersion.VERSION, TEXT_X, 963);

                g.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 24));
                g.drawString(phase, PROGRESS_X, 909);
                g.setColor(new Color(0xD7CEB5));
                g.fillRect(PROGRESS_X, BAR_Y, BAR_WIDTH, BAR_HEIGHT);
                g.setColor(new Color(0x377D80));
                g.fillRect(PROGRESS_X, BAR_Y, BAR_WIDTH * percent / 100, BAR_HEIGHT);
            } finally {
                g.dispose();
            }
        }
    }
}
