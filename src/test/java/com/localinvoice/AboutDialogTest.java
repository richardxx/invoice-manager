package com.localinvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.event.MouseEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class AboutDialogTest {
    @Test void hiddenThreeClickPlaybackStartsFromAStillFirstPanel() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<AboutDialog> dialogRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> dialogRef.set(new AboutDialog(null)));
        AboutDialog dialog = dialogRef.get();
        try {
            JPanel body = (JPanel) dialog.getContentPane();
            BorderLayout layout = (BorderLayout) body.getLayout();
            JPanel pictures = (JPanel) layout.getLayoutComponent(BorderLayout.CENTER);
            JPanel footer = (JPanel) layout.getLayoutComponent(BorderLayout.SOUTH);
            JLabel counter = (JLabel) ((BorderLayout) footer.getLayout()).getLayoutComponent(BorderLayout.EAST);
            assertEquals("关于", dialog.getTitle());
            assertEquals("1 / 6", counter.getText());
            var image = RetroArt.load("about-1.png");
            assertEquals((double) image.getWidth() / image.getHeight(),
                    (double) pictures.getPreferredSize().width / pictures.getPreferredSize().height, 0.01);

            Thread.sleep(3200);
            SwingUtilities.invokeAndWait(() -> assertEquals("1 / 6", counter.getText()));
            CountDownLatch advanced = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(() -> {
                counter.addPropertyChangeListener("text", event -> {
                    if ("2 / 6".equals(event.getNewValue())) advanced.countDown();
                });
                var picture = pictures.getComponent(0);
                for (int i = 0; i < 3; i++)
                    picture.dispatchEvent(new MouseEvent(picture, MouseEvent.MOUSE_CLICKED,
                            System.currentTimeMillis(), 0, 10, 10, 1, false));
                assertEquals("1 / 6", counter.getText());
            });
            assertTrue(advanced.await(4, TimeUnit.SECONDS), "The comic should advance after the secret clicks");
        } finally {
            SwingUtilities.invokeAndWait(dialog::dispose);
        }
    }
}
