package com.localinvoice;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiSmokeTest {
    @TempDir Path temp;

    @Test void mainDesktopViewsInitialize() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        AppService app = new AppService(temp.resolve("data"), temp);
        final MainWindow[] holder = new MainWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                holder[0] = new MainWindow(app);
                holder[0].setVisible(true);
                assertEquals("本地发票管理", holder[0].getTitle());
                assertEquals(3, holder[0].getJMenuBar().getMenuCount());
                var help = holder[0].getJMenuBar().getMenu(2);
                assertEquals("帮助", help.getText());
                assertEquals("使用方法...", help.getItem(0).getText());
                assertEquals("关于...", help.getItem(1).getText());
                javax.swing.JPanel main = (javax.swing.JPanel) holder[0].getContentPane().getComponent(1);
                assertEquals(3, ((javax.swing.JPanel) main.getComponent(1)).getComponentCount());
            } catch (Exception error) {
                throw new RuntimeException(error);
            } finally {
                if (holder[0] != null) holder[0].dispose();
            }
        });
    }
}
