package com.localinvoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InvoiceParser {
    private static final String DATE = "(20\\d{2})[年./-]\\s*(\\d{1,2})[月./-]\\s*(\\d{1,2})日?";
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:价税合计|合计金额|金额合计|票价|实付金额|支付金额)\\s*(?:[（(]小写[）)])?\\s*[:：]?\\s*[¥￥]?\\s*([0-9][0-9,]*\\.[0-9]{1,2})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL_SMALL = Pattern.compile(
            "价税合计[\\s\\S]{0,160}?[（(]小写[）)]\\s*[:：]?\\s*[¥￥]?\\s*([0-9][0-9,]*\\.[0-9]{1,2})");
    private static final Pattern CURRENCY = Pattern.compile("[¥￥]\\s*([0-9][0-9,]*\\.[0-9]{1,2})");
    private static final Pattern AIR_CURRENCY = Pattern.compile("(?i)(?:\\bCNY|[¥￥])\\s*([0-9][0-9,]*\\.[0-9]{2})");
    private static final Pattern AIR_NUMBER = Pattern.compile("(?<![0-9])([0-9]{20})(?![0-9])");
    private static final Pattern ANY_DATE = Pattern.compile(DATE);
    private static final Pattern STANDALONE_DECIMAL = Pattern.compile("(?<![0-9])([0-9][0-9,]*\\.[0-9]{2})(?![0-9])");
    private static final Pattern ISSUE_DATE = Pattern.compile("(?:开票日期|开具日期|出票日期|填开日期)\\s*[:：]?\\s*" + DATE);
    private static final Pattern TRAVEL_DATE = Pattern.compile("(?:乘车日期|出发日期|旅行日期)\\s*[:：]?\\s*" + DATE);
    private static final Pattern NUMBER = Pattern.compile("(?:发票号码|票据号码|发票号)\\s*[:：]?\\s*([0-9]{8,20})");
    private static final Pattern ISSUER = Pattern.compile("(?:销\\s*售\\s*方(?:\\s*名\\s*称)?|销方名称|开票方(?:名称)?|经营者名称|销\\s*名\\s*称|填\\s*开\\s*单\\s*位)\\s*[:：]?\\s*([^\\r\\n]{2,70})");
    private static final Pattern REGION_SELLER_NAME = Pattern.compile("名\\s*称\\s*[:：]\\s*([^\\r\\n]{2,70})");
    private static final Pattern BUYER = Pattern.compile("(?:购\\s*买\\s*方(?:\\s*名\\s*称)?|购方名称|购\\s*名\\s*称)\\s*[:：]?\\s*([^\\r\\n]{2,70})");
    private static final Pattern TRAVELER = Pattern.compile("(?:旅客(?:姓名)?|乘车人)\\s*[:：]?\\s*([^\\r\\n]{2,35})");

    public Invoice parse(String text, boolean fromOcr) {
        String normalized = text == null ? "" : text.replace('\u00a0', ' ').replace('　', ' ')
                .replace("（小写）", "(小写)").replaceAll("[ \\t]+", " ");
        boolean airItinerary = containsAny(normalized, "电子客票号码", "航空运输电子客票", "民航发展基金", "燃油附加费");
        Invoice invoice = new Invoice();
        invoice.issuer = value(ISSUER, normalized);
        invoice.buyer = value(BUYER, normalized);
        invoice.traveler = value(TRAVELER, normalized);
        if (airItinerary && invoice.traveler != null && !invoice.traveler.matches("[\\p{IsHan}·]{2,8}"))
            invoice.traveler = null;
        invoice.invoiceNumber = value(NUMBER, normalized);
        if (invoice.invoiceNumber == null && airItinerary) invoice.invoiceNumber = value(AIR_NUMBER, normalized);
        invoice.issueDate = date(ISSUE_DATE, normalized);
        invoice.travelDate = date(TRAVEL_DATE, normalized);
        if (invoice.travelDate == null && airItinerary) invoice.travelDate = airTravelDate(normalized);
        invoice.amountCents = airItinerary ? airTotal(normalized) : null;
        if (invoice.amountCents == null) invoice.amountCents = cents(value(AMOUNT, normalized));
        if (invoice.amountCents == null) invoice.amountCents = cents(value(TOTAL_SMALL, normalized));
        boolean railway = containsAny(normalized, "铁路电子客票", "铁路车票", "高铁", "动车", "车次", "乘车日期");
        if (invoice.amountCents == null && railway) invoice.amountCents = uniqueAmount(CURRENCY, normalized);
        if (invoice.amountCents == null && (railway || fromOcr))
            invoice.amountCents = uniqueAmount(STANDALONE_DECIMAL, normalized);
        if (invoice.issuer == null && railway && normalized.contains("中国铁路")) invoice.issuer = "中国铁路";
        invoice.category = classify(normalized, railway || airItinerary);
        invoice.reimbursementMonth = invoice.issueDate == null ? null : YearMonth.from(invoice.issueDate);
        invoice.recognitionSource = fromOcr ? "OCR" : "PDF_TEXT";
        invoice.reviewStatus = fromOcr || invoice.issuer == null || invoice.issueDate == null
                || invoice.amountCents == null || invoice.amountCents <= 0 || invoice.category == Category.OTHER
                || containsAny(normalized, "红字发票", "退票费", "已作废", "发票作废")
                ? ReviewStatus.NEEDS_REVIEW : ReviewStatus.READY;
        return invoice;
    }

    private static Category classify(String text, boolean railway) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (railway || containsAny(lower, "交通", "出租车", "滴滴", "机票", "航空运输", "客运")) return Category.TRANSPORT;
        if (containsAny(lower, "住宿", "酒店", "宾馆", "客房")) return Category.LODGING;
        if (containsAny(lower, "餐饮", "餐费", "饭店", "食品", "外卖")) return Category.DINING;
        if (containsAny(lower, "服务", "咨询", "技术", "维修", "劳务")) return Category.SERVICE;
        return Category.OTHER;
    }

    private static String value(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return null;
        String value = matcher.group(1).strip().replaceAll("^[：:]\\s*", "");
        value = value.replaceAll("\\s+(?:销\\s*名称|销售方(?:名称)?|销方名称)\\s*[:：].*$", "").strip();
        value = value.replaceAll("\\s+(?:纳税人识别号|统一社会信用代码|地址|电话|税号).*$", "").strip();
        value = value.replaceAll("\\s+(?:填开日期|出票日期)\\s*[:：].*$", "").strip();
        return value.isEmpty() ? null : value;
    }

    static String sellerFromRegion(String text) {
        String candidate = value(REGION_SELLER_NAME, text == null ? "" : text);
        if (candidate == null || candidate.length() > 50 || !candidate.matches(".*\\p{IsHan}.*\\p{IsHan}.*")
                || candidate.contains("购买方") || candidate.contains("纳税人识别号")) return null;
        return candidate;
    }

    private static Long airTotal(String text) {
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].contains("合计")) continue;
            for (int j = i; j < Math.min(i + 4, lines.length); j++) {
                if (j > i && lines[j].contains("电子客票号码")) break;
                Matcher amounts = AIR_CURRENCY.matcher(lines[j]);
                String last = null;
                while (amounts.find()) last = amounts.group(1);
                if (last != null) return cents(last);
            }
        }
        return null;
    }

    private static LocalDate airTravelDate(String text) {
        int ticket = text.indexOf("电子客票号码");
        String journey = ticket < 0 ? text : text.substring(0, ticket);
        Matcher matcher = ANY_DATE.matcher(journey);
        while (matcher.find()) {
            LocalDate found = date(matcher);
            if (found != null) return found;
        }
        return null;
    }

    private static LocalDate date(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return null;
        return date(matcher);
    }

    private static LocalDate date(Matcher matcher) {
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Long cents(String amount) {
        if (amount == null) return null;
        try {
            return new BigDecimal(amount.replace(",", "")).movePointRight(2).longValueExact();
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Long uniqueAmount(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return null;
        String first = matcher.group(1);
        return matcher.find() ? null : cents(first);
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }
}
