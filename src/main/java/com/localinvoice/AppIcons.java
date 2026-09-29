package com.localinvoice;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

public final class AppIcons {
    public enum Kind { RECEIPT, POOL, PEOPLE, SETTINGS, CHEVRON_DOWN, CHEVRON_LEFT, CHEVRON_RIGHT,
        CHECK, DOT, WARNING, SPINNER }

    private AppIcons() { }

    public static Icon of(Kind kind, Color color) { return new VectorIcon(kind, color, 0); }

    public static Icon spinner(Color color, int frame) { return new VectorIcon(Kind.SPINNER, color, frame); }

    private record VectorIcon(Kind kind, Color color, int frame) implements Icon {
        @Override public int getIconWidth() { return 20; }
        @Override public int getIconHeight() { return 20; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (kind) {
                case RECEIPT -> {
                    g.drawRoundRect(4, 2, 12, 16, 2, 2);
                    g.drawLine(7, 7, 13, 7); g.drawLine(7, 11, 13, 11); g.drawLine(7, 15, 11, 15);
                }
                case POOL -> {
                    g.drawRoundRect(2, 3, 16, 13, 2, 2);
                    g.drawLine(5, 7, 15, 7); g.drawLine(5, 10, 13, 10);
                    g.drawLine(5, 18, 15, 18);
                }
                case PEOPLE -> {
                    g.drawOval(7, 2, 6, 6);
                    g.drawArc(4, 9, 12, 10, 0, 180);
                    g.drawArc(1, 10, 7, 8, 0, 140);
                    g.drawArc(12, 10, 7, 8, 40, 140);
                }
                case SETTINGS -> {
                    g.drawOval(6, 6, 8, 8); g.drawOval(9, 9, 2, 2);
                    for (int angle = 0; angle < 360; angle += 45) {
                        double radians = Math.toRadians(angle);
                        g.drawLine((int) (10 + 6 * Math.cos(radians)), (int) (10 + 6 * Math.sin(radians)),
                                (int) (10 + 9 * Math.cos(radians)), (int) (10 + 9 * Math.sin(radians)));
                    }
                }
                case CHEVRON_DOWN -> { g.drawLine(5, 8, 10, 13); g.drawLine(10, 13, 15, 8); }
                case CHEVRON_LEFT -> { g.drawLine(12, 5, 7, 10); g.drawLine(7, 10, 12, 15); }
                case CHEVRON_RIGHT -> { g.drawLine(8, 5, 13, 10); g.drawLine(13, 10, 8, 15); }
                case CHECK -> { g.drawLine(4, 10, 8, 14); g.drawLine(8, 14, 16, 5); }
                case DOT -> g.fillOval(7, 7, 6, 6);
                case WARNING -> {
                    g.drawLine(10, 2, 18, 17); g.drawLine(18, 17, 2, 17); g.drawLine(2, 17, 10, 2);
                    g.drawLine(10, 7, 10, 12); g.fillOval(9, 14, 2, 2);
                }
                case SPINNER -> {
                    g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawArc(3, 3, 14, 14, frame * 45, 260);
                }
            }
            g.dispose();
        }
    }
}
