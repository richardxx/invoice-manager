package com.localinvoice;

import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import org.junit.jupiter.api.Test;

class AppIconsTest {
    @Test void everyInterfaceIconPaintsVisiblePixelsWithoutAFont() {
        for (AppIcons.Kind kind : AppIcons.Kind.values()) {
            assertPaints(AppIcons.of(kind, Color.WHITE));
        }
        for (int frame = 0; frame < 8; frame++) {
            assertPaints(AppIcons.spinner(Color.WHITE, frame));
        }
    }

    private static void assertPaints(Icon icon) {
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try { icon.paintIcon(null, graphics, 0, 0); }
        finally { graphics.dispose(); }
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) return;
            }
        }
        fail("Icon painted no visible pixels: " + icon);
    }
}
