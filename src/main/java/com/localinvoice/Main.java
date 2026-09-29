package com.localinvoice;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import com.formdev.flatlaf.FlatLightLaf;

public final class Main {
    private static WorkDirectoryService.Lock runningLock;
    private record StartupProgress(int percent, String label) { }
    private record StartupContext(AppService app, WorkDirectoryService work, boolean rootOverridden) { }
    private Main() { }

    public static void main(String[] args) {
        String dataRootArg = null;
        String migrationArg = null;
        for (String arg : args) {
            if (arg.startsWith("--data-root=")) dataRootArg = arg.substring("--data-root=".length());
            if (arg.startsWith("--migrate-work-dir=")) migrationArg = arg.substring("--migrate-work-dir=".length());
        }
        final String requestedDataRoot = dataRootArg;
        final String requestedMigration = migrationArg;
        SwingUtilities.invokeLater(() -> {
            try {
                configureLookAndFeel();
                SplashWindow splash = new SplashWindow();
                splash.setVisible(true);
                new SwingWorker<StartupContext, StartupProgress>() {
                    @Override protected StartupContext doInBackground() throws Exception {
                        publish(new StartupProgress(10, "正在准备工作目录..."));
                        String local = System.getenv("LOCALAPPDATA");
                        Path localBase = local != null ? Path.of(local)
                                : Path.of(System.getProperty("user.home"), ".local");
                        String override = System.getProperty("invoice.data.root");
                        boolean rootOverridden = requestedDataRoot != null || override != null;
                        Path overrideRoot = rootOverridden
                                ? Path.of(requestedDataRoot != null ? requestedDataRoot : override).toAbsolutePath().normalize()
                                : null;
                        WorkDirectoryService work = rootOverridden
                                ? new WorkDirectoryService(overrideRoot,
                                        overrideRoot.resolveSibling(overrideRoot.getFileName() + "-bootstrap"))
                                : new WorkDirectoryService(local != null ? localBase.resolve("LocalInvoiceManager")
                                        : localBase.resolve("local-invoice-manager"),
                                        localBase.resolve("InvoiceManageBootstrap"));
                        WorkDirectoryService.Lock lock = null;
                        try {
                            lock = work.acquireLock();
                            publish(new StartupProgress(35, "正在检查已有资料..."));
                            if (requestedMigration != null) work.migrate(Path.of(requestedMigration));
                            Path root = requestedMigration != null ? work.current()
                                    : requestedDataRoot != null ? Path.of(requestedDataRoot)
                                    : override != null ? Path.of(override) : work.current();
                            publish(new StartupProgress(65, "正在读取人员和发票数据..."));
                            AppService app = new AppService(root, Path.of(System.getProperty("user.dir")));
                            publish(new StartupProgress(85, "正在准备主界面..."));
                            runningLock = lock;
                            return new StartupContext(app, work, rootOverridden);
                        } catch (Exception error) {
                            if (lock != null) try { lock.close(); }
                            catch (Exception closing) { error.addSuppressed(closing); }
                            throw error;
                        }
                    }

                    @Override protected void process(List<StartupProgress> updates) {
                        StartupProgress latest = updates.getLast();
                        splash.update(latest.percent(), latest.label());
                    }

                    @Override protected void done() {
                        try {
                            StartupContext startup = get();
                            MainWindow window = new MainWindow(startup.app(), startup.work(),
                                    startup.rootOverridden());
                            splash.update(100, "加载完成，欢迎使用！");
                            Timer reveal = new Timer(350, event -> {
                                splash.dispose();
                                window.setVisible(true);
                            });
                            reveal.setRepeats(false);
                            reveal.start();
                        } catch (Exception error) {
                            splash.dispose();
                            Throwable cause = error.getCause() == null ? error : error.getCause();
                            String title = requestedMigration == null ? "启动失败" : "工作目录迁移失败，原目录仍保留";
                            JOptionPane.showMessageDialog(null, cause.getMessage(), title, JOptionPane.ERROR_MESSAGE);
                            if (runningLock != null) try { runningLock.close(); }
                            catch (Exception ignored) { }
                            runningLock = null;
                        }
                    }
                }.execute();
            } catch (Exception error) {
                JOptionPane.showMessageDialog(null, error.getMessage(), "启动失败", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private static void configureLookAndFeel() {
        FlatLightLaf.setup();
        UIManager.put("Button.arc", 3);
        UIManager.put("Component.arc", 3);
        UIManager.put("TextComponent.arc", 3);
        UIManager.put("Panel.background", RetroArt.PAPER);
        UIManager.put("Viewport.background", RetroArt.PAPER);
        UIManager.put("Table.background", new java.awt.Color(0xFAF8EE));
        UIManager.put("Table.alternateRowColor", new java.awt.Color(0xEEE9D9));
        UIManager.put("TableHeader.background", new java.awt.Color(0xD4DECE));
        UIManager.put("Button.background", new java.awt.Color(0xD7DED0));
        UIManager.put("TextField.background", new java.awt.Color(0xFFFDF4));
        UIManager.put("ComboBox.background", new java.awt.Color(0xFFFDF4));
        UIManager.put("List.background", new java.awt.Color(0xFFFDF4));
        UIManager.put("ScrollBar.width", 14);
        UIManager.put("Table.rowHeight", 36);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", true);
        UIManager.put("Table.gridColor", new java.awt.Color(0xC2C6B6));
        UIManager.put("Table.selectionBackground", new java.awt.Color(0xAFD7CF));
        UIManager.put("Table.selectionForeground", RetroArt.INK);
        UIManager.put("defaultFont", new java.awt.Font("Microsoft YaHei UI", java.awt.Font.PLAIN, 13));
    }

    static void restartForMigration(Path target) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "javaw.exe" : "java");
        Path code = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> command = new ArrayList<>(List.of(java.toString(), "--enable-native-access=ALL-UNNAMED"));
        if (Files.isRegularFile(code) && code.toString().toLowerCase().endsWith(".jar")) {
            command.add("-jar");
            command.add(code.toString());
        } else {
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(Main.class.getName());
        }
        command.add("--migrate-work-dir=" + target.toAbsolutePath().normalize());
        new ProcessBuilder(command).directory(Path.of(System.getProperty("user.dir")).toFile()).start();
        System.exit(0);
    }
}
