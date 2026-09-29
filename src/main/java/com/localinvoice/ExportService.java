package com.localinvoice;

import com.ibm.icu.text.Transliterator;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ExportService {
    private final AppService app;

    public ExportService(AppService app) { this.app = app; }

    public record ExportItem(String invoiceId, String ownerId, String ownerName, long amountCents,
                             Category category, String fileName) {
        public ExportItem withFileName(String name) {
            return new ExportItem(invoiceId, ownerId, ownerName, amountCents, category, name);
        }
    }

    public List<ExportItem> previewMonth(YearMonth month) throws Exception {
        List<Invoice> invoices = new ArrayList<>(app.countedInMonth(month));
        if (invoices.isEmpty()) throw new IllegalArgumentException("本月没有可导出的发票");
        invoices.sort(Comparator.comparing((Invoice i) -> i.ownerId)
                .thenComparing(i -> i.category.name()).thenComparing(i -> i.id));
        Map<String, Person> people = new HashMap<>();
        for (Person person : app.people()) people.put(person.id(), person);
        Map<String, Integer> names = new HashMap<>();
        List<ExportItem> preview = new ArrayList<>();
        for (Invoice invoice : invoices) {
            Person owner = people.get(invoice.ownerId);
            if (owner == null) throw new IllegalStateException("发票归属人员不存在");
            String base = pinyin(owner.name()) + "-" + invoice.category.label() + "-"
                    + BigDecimal.valueOf(invoice.amountCents, 2).toPlainString();
            int occurrence = names.merge(base, 1, Integer::sum);
            String fileName = base + (occurrence == 1 ? "" : "-" + occurrence) + ".pdf";
            preview.add(new ExportItem(invoice.id, invoice.ownerId, owner.name(), invoice.amountCents,
                    invoice.category, fileName));
        }
        return List.copyOf(preview);
    }

    public int exportMonth(YearMonth month, Path parentDirectory) throws Exception {
        return exportMonth(month, parentDirectory, previewMonth(month));
    }

    public int exportMonth(YearMonth month, Path parentDirectory, List<ExportItem> approved) throws Exception {
        validateFileNames(approved);
        Map<String, Invoice> current = new HashMap<>();
        for (Invoice invoice : app.countedInMonth(month)) current.put(invoice.id, invoice);
        if (current.size() != approved.size()) throw new IllegalStateException("预览后发票数据已变化，请重新打开导出");
        Map<String, Person> people = new HashMap<>();
        for (Person person : app.people()) people.put(person.id(), person);
        List<Path> originals = new ArrayList<>();
        for (ExportItem item : approved) {
            Invoice invoice = current.remove(item.invoiceId());
            Person owner = people.get(item.ownerId());
            if (invoice == null || owner == null || !item.ownerId().equals(invoice.ownerId)
                    || !item.ownerName().equals(owner.name()) || item.amountCents() != invoice.amountCents
                    || item.category() != invoice.category)
                throw new IllegalStateException("预览后发票数据已变化，请重新打开导出");
            Path original = app.original(invoice);
            if (!Files.isRegularFile(original)) throw new IllegalStateException("原始文件缺失: " + invoice.originalName);
            originals.add(original);
        }
        if (!current.isEmpty()) throw new IllegalStateException("预览后发票数据已变化，请重新打开导出");
        Path parent = parentDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(parent)) throw new IllegalArgumentException("请选择有效的导出目录");
        Path target = monthFolder(month, parent);
        if (Files.exists(target)) throw new IllegalArgumentException("目标月份文件夹已存在：" + target);
        Path temporary = Files.createTempDirectory(parent, ".invoice-export-");
        try {
            for (int index = 0; index < approved.size(); index++)
                Files.copy(originals.get(index), temporary.resolve(approved.get(index).fileName()));
            Files.move(temporary, target);
            return approved.size();
        } finally {
            if (Files.exists(temporary)) {
                try (var files = Files.list(temporary)) {
                    for (Path file : files.toList()) Files.deleteIfExists(file);
                }
                Files.deleteIfExists(temporary);
            }
        }
    }

    public static Path monthFolder(YearMonth month, Path parentDirectory) {
        return parentDirectory.toAbsolutePath().normalize().resolve(String.format("%04d%02d",
                month.getYear(), month.getMonthValue()));
    }

    static void validateFileNames(List<ExportItem> items) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("没有可导出的发票");
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            String name = items.get(index).fileName();
            if (name == null || name.isBlank() || name.length() > 200 || !name.equals(name.strip())
                    || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")
                    || name.chars().anyMatch(ch -> ch < 32 || "<>:\"/\\|?*".indexOf(ch) >= 0))
                throw new IllegalArgumentException("第 " + (index + 1) + " 行的导出文件名无效；请使用单个 .pdf 文件名");
            String stem = name.substring(0, name.length() - 4);
            if (stem.isBlank() || stem.endsWith(".") || stem.endsWith(" ")
                    || stem.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
                throw new IllegalArgumentException("第 " + (index + 1) + " 行的导出文件名不能在 Windows 中使用");
            String unique = Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            if (!seen.add(unique)) throw new IllegalArgumentException("导出文件名重复：" + name);
        }
    }

    public static String pinyin(String name) {
        if ("公司".equals(name)) return "gongsi";
        Transliterator transliterator = Transliterator.getInstance("Han-Latin; Latin-ASCII");
        String plain = transliterator.transliterate(name).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
        return plain.isEmpty() ? "owner" : plain;
    }
}
