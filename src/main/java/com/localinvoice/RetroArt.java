package com.localinvoice;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JPanel;

final class RetroArt {
    static final Color PAPER = new Color(0xF7F1DC);
    static final Color BLUE = new Color(0x244B70);
    static final Color INK = new Color(0x263241);

    private RetroArt() { }

    static BufferedImage load(String name) {
        try (var input = RetroArt.class.getResourceAsStream("/art/" + name)) {
            if (input == null) throw new IllegalStateException("插画资源缺失: " + name);
            BufferedImage image = ImageIO.read(input);
            if (image == null) throw new IllegalStateException("插画资源无法读取: " + name);
            return image;
        } catch (IOException error) {
            throw new IllegalStateException("插画资源无法读取: " + name, error);
        }
    }

    static JPanel panel(BufferedImage image) {
        JPanel panel = new JPanel() {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                double scale = Math.min((double) getWidth() / image.getWidth(),
                        (double) getHeight() / image.getHeight());
                int width = (int) Math.round(image.getWidth() * scale);
                int height = (int) Math.round(image.getHeight() * scale);
                g.drawImage(image, (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
                g.dispose();
            }
        };
        panel.setBackground(PAPER);
        panel.setBorder(BorderFactory.createLoweredBevelBorder());
        return panel;
    }
}
