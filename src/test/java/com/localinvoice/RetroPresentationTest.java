package com.localinvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class RetroPresentationTest {
    @Test void splashAndAllSixComicScenesArePackagedAndReadable() throws Exception {
        var scenes = AboutDialog.scenes();
        assertEquals(6, scenes.size());
        var names = new HashSet<String>();
        names.add("splash.png");
        for (var scene : scenes) {
            assertTrue(names.add(scene.image()));
            assertTrue(!scene.caption().isBlank());
        }
        for (String name : names) {
            try (var input = RetroArt.class.getResourceAsStream("/art/" + name)) {
                assertNotNull(input, name);
                var image = ImageIO.read(input);
                assertNotNull(image, name);
                assertTrue(image.getWidth() >= 800 && image.getHeight() >= 600, name);
            }
        }
    }
}
