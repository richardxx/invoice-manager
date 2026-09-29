package com.localinvoice;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.ImageIcon;

public final class AvatarPresets {
    public enum Mood { SMILE, WINK, LAUGH, FOCUS, SLEEPY, SURPRISED, CURIOUS, SHY, COOL, PROUD }
    public enum Gesture { NONE, WAVE, PEACE, CHEER, THUMBS_UP, THINK }
    public enum Hair { SHORT, SIDE, CURLS, BUN }

    public record Option(String id, String label, Color background, Color shirt, Color hair, Color skin,
            Mood mood, Gesture gesture, Hair hairStyle) {
        @Override public String toString() { return label; }
    }

    private static final List<Option> OPTIONS = List.of(
            new Option("preset:coral", "挥手珊瑚", color(0xF7A9A0), color(0xE66572), color(0x46323B), color(0xFAD6B3), Mood.SMILE, Gesture.WAVE, Hair.SIDE),
            new Option("preset:sky", "眨眼晴空", color(0x8BCBE7), color(0x487EBC), color(0x282F46), color(0xF3C69E), Mood.WINK, Gesture.PEACE, Hair.SHORT),
            new Option("preset:mint", "大笑薄荷", color(0x9DD8BE), color(0x398F75), color(0x543B36), color(0xE6B485), Mood.LAUGH, Gesture.CHEER, Hair.CURLS),
            new Option("preset:violet", "专注紫藤", color(0xC6B2E6), color(0x7B64B0), color(0x302848), color(0xF2C8AF), Mood.FOCUS, Gesture.NONE, Hair.BUN),
            new Option("preset:sun", "困困暖阳", color(0xF3D38C), color(0xCA9048), color(0x47302D), color(0xBD805F), Mood.SLEEPY, Gesture.NONE, Hair.SIDE),
            new Option("preset:rose", "惊喜玫瑰", color(0xECB7CC), color(0xBB637E), color(0x563E32), color(0xEDC19C), Mood.SURPRISED, Gesture.CHEER, Hair.BUN),
            new Option("preset:teal", "点赞青绿", color(0x8CCECF), color(0x397D91), color(0x23343A), color(0xD7A17B), Mood.PROUD, Gesture.THUMBS_UP, Hair.SHORT),
            new Option("preset:peach", "思考蜜桃", color(0xF2BC9E), color(0xD67D5B), color(0x594039), color(0xF5D0AE), Mood.CURIOUS, Gesture.THINK, Hair.CURLS),
            new Option("preset:indigo", "酷酷靛蓝", color(0x98A9DF), color(0x4E5F9B), color(0x232B39), color(0xE8BA94), Mood.COOL, Gesture.NONE, Hair.SHORT),
            new Option("preset:lime", "元气青柠", color(0xC5DE94), color(0x7EAB54), color(0x4D3B31), color(0xF6CDA4), Mood.LAUGH, Gesture.WAVE, Hair.BUN),
            new Option("preset:blush", "腼腆粉桃", color(0xEFC4C5), color(0xB47A8D), color(0x593E40), color(0xEFC2A3), Mood.SHY, Gesture.NONE, Hair.SIDE),
            new Option("preset:ocean", "比耶海蓝", color(0x8FC3D9), color(0x3A7697), color(0x2B3642), color(0xD6A47C), Mood.SMILE, Gesture.PEACE, Hair.CURLS),
            new Option("preset:amber", "加油琥珀", color(0xE8C18C), color(0xB77C4B), color(0x4A3332), color(0xF1C79E), Mood.PROUD, Gesture.CHEER, Hair.SHORT),
            new Option("preset:lilac", "好奇丁香", color(0xCBBCE9), color(0x8C78B5), color(0x3D3148), color(0xE9B998), Mood.CURIOUS, Gesture.WAVE, Hair.BUN),
            new Option("preset:jade", "安心青玉", color(0xA5D5C7), color(0x5A9A88), color(0x363A35), color(0xC79072), Mood.SLEEPY, Gesture.THUMBS_UP, Hair.CURLS),
            new Option("preset:berry", "开心莓果", color(0xDCA0B6), color(0xA94E78), color(0x493047), color(0xF0C9A8), Mood.WINK, Gesture.WAVE, Hair.SIDE));

    private AvatarPresets() { }

    private static Color color(int rgb) { return new Color(rgb); }

    public static List<Option> options() { return OPTIONS; }

    public static String valid(String id) {
        if (id == null) return OPTIONS.getFirst().id();
        return OPTIONS.stream().anyMatch(option -> option.id().equals(id)) ? id
                : throwInvalid();
    }

    private static String throwInvalid() { throw new IllegalArgumentException("请选择预设头像"); }

    public static ImageIcon icon(String id, int size) {
        Option option = OPTIONS.stream().filter(item -> item.id().equals(id)).findFirst().orElse(OPTIONS.getFirst());
        int canvas = Math.max(size, 48);
        BufferedImage image = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(canvas / 64.0, canvas / 64.0);
        g.setColor(option.background()); g.fillOval(0, 0, 64, 64);
        drawArms(g, option);
        g.setColor(option.shirt()); g.fillOval(7, 43, 50, 42);
        g.setColor(option.hair()); g.fillOval(16, 9, 32, 39);
        g.setColor(option.skin()); g.fillOval(19, 17, 26, 32);
        drawHair(g, option);
        drawFace(g, option);
        if (option.gesture() == Gesture.THINK) {
            g.setColor(option.shirt()); g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(47, 50, 41, 43);
            g.setColor(option.skin()); g.fillOval(37, 39, 8, 8);
        }
        g.dispose();
        return new ImageIcon(image.getScaledInstance(size, size, java.awt.Image.SCALE_SMOOTH));
    }

    private static void drawArms(Graphics2D g, Option option) {
        g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(option.shirt());
        if (option.gesture() == Gesture.WAVE || option.gesture() == Gesture.CHEER
                || option.gesture() == Gesture.THUMBS_UP) g.drawLine(48, 51, 55, 29);
        if (option.gesture() == Gesture.PEACE || option.gesture() == Gesture.CHEER) g.drawLine(16, 51, 9, 29);
        g.setColor(option.skin());
        if (option.gesture() == Gesture.WAVE || option.gesture() == Gesture.CHEER) g.fillOval(51, 19, 10, 13);
        if (option.gesture() == Gesture.THUMBS_UP) {
            g.fillRoundRect(51, 24, 10, 10, 4, 4);
            g.fillRoundRect(54, 17, 4, 12, 2, 2);
        }
        if (option.gesture() == Gesture.PEACE) {
            g.fillOval(5, 24, 9, 9);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(7, 25, 5, 17); g.drawLine(11, 25, 13, 17);
        } else if (option.gesture() == Gesture.CHEER) g.fillOval(4, 19, 10, 13);
    }

    private static void drawHair(Graphics2D g, Option option) {
        g.setColor(option.hair());
        switch (option.hairStyle()) {
            case SHORT -> g.fillArc(17, 11, 32, 27, 0, 180);
            case SIDE -> {
                g.fillArc(16, 10, 34, 29, 0, 180);
                g.fillOval(17, 16, 9, 14);
            }
            case CURLS -> {
                for (int x = 17; x <= 39; x += 7) g.fillOval(x, 10 + (x % 3), 10, 10);
                g.fillOval(16, 18, 8, 12);
            }
            case BUN -> {
                g.fillOval(35, 5, 13, 13);
                g.fillArc(17, 11, 32, 27, 0, 180);
            }
        }
    }

    private static void drawFace(Graphics2D g, Option option) {
        g.setColor(new Color(0x352C35));
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (option.mood()) {
            case SLEEPY -> { g.drawArc(24, 31, 5, 3, 180, 180); g.drawArc(36, 31, 5, 3, 180, 180); }
            case WINK -> { g.drawArc(24, 31, 5, 3, 180, 180); g.fillOval(37, 31, 2, 3); }
            case SURPRISED -> { g.drawOval(24, 30, 4, 5); g.drawOval(36, 30, 4, 5); }
            case FOCUS -> {
                g.drawOval(22, 29, 9, 8); g.drawOval(34, 29, 9, 8); g.drawLine(31, 33, 34, 33);
                g.fillOval(25, 32, 2, 2); g.fillOval(37, 32, 2, 2);
            }
            case COOL -> {
                g.fillRoundRect(22, 29, 10, 7, 2, 2); g.fillRoundRect(33, 29, 10, 7, 2, 2);
                g.drawLine(31, 31, 34, 31);
            }
            default -> { g.fillOval(25, 31, 2, 3); g.fillOval(37, 31, 2, 3); }
        }
        switch (option.mood()) {
            case LAUGH -> {
                g.fillOval(29, 37, 8, 8);
                g.setColor(new Color(0xE97881)); g.fillArc(30, 41, 6, 3, 0, 180);
            }
            case SURPRISED -> g.drawOval(30, 38, 5, 7);
            case FOCUS, SLEEPY, CURIOUS -> g.drawLine(30, 41, 36, 41);
            default -> g.drawArc(28, 37, 9, 6, 190, 160);
        }
        if (option.mood() == Mood.SHY) {
            g.setColor(new Color(232, 141, 147, 150));
            g.fillOval(21, 37, 5, 3); g.fillOval(39, 37, 5, 3);
        }
    }
}
