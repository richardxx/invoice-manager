package com.localinvoice;

import java.awt.Component;
import java.awt.Image;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.ImageIcon;
import javax.swing.JOptionPane;

public final class Ui {
    private Ui() { }

    public static String money(Long cents) {
        return cents == null ? "—" : "¥" + BigDecimal.valueOf(cents, 2).toPlainString();
    }

    public static void error(Component parent, Exception error) {
        JOptionPane.showMessageDialog(parent, error.getMessage() == null ? error.toString() : error.getMessage(),
                "操作失败", JOptionPane.ERROR_MESSAGE);
    }

    public static ImageIcon avatar(AppService app, Person person, int size) {
        if (person.avatarPath() != null && person.avatarPath().startsWith("preset:"))
            return AvatarPresets.icon(person.avatarPath(), size);
        if (person.avatarPath() == null) return placeholder(person, size);
        try {
            Path file = app.root().resolve(person.avatarPath()).normalize();
            if (!file.startsWith(app.root().resolve("avatars")) || !Files.isRegularFile(file)) return placeholder(person, size);
            Image image = new ImageIcon(file.toString()).getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH);
            return new ImageIcon(image);
        } catch (RuntimeException error) {
            return placeholder(person, size);
        }
    }

    private static ImageIcon placeholder(Person person, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(person.company() ? new Color(47, 97, 142) : new Color(89, 111, 166));
        graphics.fillOval(0, 0, size - 1, size - 1);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font("SansSerif", Font.BOLD, Math.max(14, size / 2)));
        String initial = person.company() ? "公" : person.name().substring(0, 1);
        java.awt.FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(initial, (size - metrics.stringWidth(initial)) / 2,
                (size + metrics.getAscent() - metrics.getDescent()) / 2);
        graphics.dispose();
        return new ImageIcon(image);
    }
}
