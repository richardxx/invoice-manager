package com.localinvoice;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

public final class BankExcelExportService {
    private static final String TEMPLATE = "/templates/MixedOtherPaymentExcelTemplate.xls";
    private static final String SHEET = "其他代发批量文件-混合";
    private static final String[] HEADERS = {"卡号/账号", "户名", "金额", "收款行类型", "收款行CNAPS号",
            "证件类型", "证件号码", "用途", "附言"};
    private final AppService app;

    public BankExcelExportService(AppService app) { this.app = app; }

    public record PaymentRow(Person person, long amountCents) { }

    public List<PaymentRow> previewMonth(YearMonth month) throws Exception {
        Map<String, Long> totals = new HashMap<>();
        for (Invoice invoice : app.countedInMonth(month))
            totals.merge(invoice.ownerId, invoice.amountCents, Math::addExact);
        List<PaymentRow> rows = new ArrayList<>();
        List<String> incomplete = new ArrayList<>();
        for (Person person : app.people()) {
            Long cents = totals.remove(person.id());
            if (cents == null || person.company()) continue;
            if (!person.payment().missingFields().isEmpty())
                incomplete.add(person.name() + "（" + String.join("、", person.payment().missingFields()) + "）");
            if (cents > 99_999_999_999_999L)
                throw new IllegalArgumentException(person.name() + "的金额超出 Excel 安全精度范围");
            rows.add(new PaymentRow(person, cents));
        }
        if (!totals.isEmpty()) throw new IllegalStateException("有报销发票的人员资料不存在");
        if (rows.isEmpty()) throw new IllegalArgumentException("本月没有已核对、可导出的个人报销金额");
        if (!incomplete.isEmpty())
            throw new IllegalArgumentException("请先在人员管理中补齐收款资料：\n" + String.join("\n", incomplete));
        if (rows.size() > 65535) throw new IllegalArgumentException("人员数量超出 XLS 格式上限");
        return List.copyOf(rows);
    }

    public int exportMonth(YearMonth month, Path destination) throws Exception {
        List<PaymentRow> rows = previewMonth(month);
        Path target = destination.toAbsolutePath().normalize();
        if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xls"))
            throw new IllegalArgumentException("银行报销表必须保存为 .xls 文件");
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IllegalArgumentException("请选择有效的保存目录");
        Path temporary = Files.createTempFile(parent, ".bank-export-", ".xls");
        try {
            try (InputStream template = BankExcelExportService.class.getResourceAsStream(TEMPLATE)) {
                if (template == null) throw new IllegalStateException("银行 Excel 模板缺失");
                try (HSSFWorkbook workbook = new HSSFWorkbook(template)) {
                    Sheet sheet = workbook.getSheet(SHEET);
                    if (sheet == null) throw new IllegalStateException("银行 Excel 模板缺少指定工作表");
                    Row header = sheet.getRow(0);
                    for (int column = 0; column < HEADERS.length; column++) {
                        if (header == null || header.getCell(column) == null
                                || !HEADERS[column].equals(header.getCell(column).getStringCellValue()))
                            throw new IllegalStateException("银行 Excel 模板表头与程序不匹配");
                    }
                    CellStyle text = workbook.createCellStyle();
                    text.setDataFormat(workbook.createDataFormat().getFormat("@"));
                    CellStyle amount = workbook.createCellStyle();
                    amount.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
                    for (int index = 0; index < rows.size(); index++) {
                        PaymentRow paymentRow = rows.get(index);
                        PaymentDetails payment = paymentRow.person().payment();
                        Row row = sheet.createRow(index + 1);
                        setText(row, 0, payment.accountNumber(), text);
                        setText(row, 1, payment.accountName(), text);
                        var amountCell = row.createCell(2);
                        amountCell.setCellStyle(amount);
                        amountCell.setCellValue(paymentRow.amountCents() / 100.0);
                        setText(row, 3, payment.bankType().label(), text);
                        setText(row, 4, payment.cnapsNumber(), text);
                        setText(row, 5, payment.identityType(), text);
                        setText(row, 6, payment.identityNumber(), text);
                        setText(row, 7, "报销", text);
                        setText(row, 8, month.toString() + " 报销", text);
                    }
                    try (OutputStream output = Files.newOutputStream(temporary)) { workbook.write(output); }
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            return rows.size();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void setText(Row row, int column, String value, CellStyle style) {
        var cell = row.createCell(column);
        cell.setCellStyle(style);
        cell.setCellValue(value);
    }
}
