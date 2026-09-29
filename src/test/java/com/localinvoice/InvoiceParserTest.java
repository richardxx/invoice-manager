package com.localinvoice;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class InvoiceParserTest {
    private final InvoiceParser parser = new InvoiceParser();

    @Test void ordinaryInvoiceUsesTaxInclusiveTotal() {
        String text = "电子发票\n发票号码: 20260927000000000001\n开票日期: 2026年09月07日\n"
                + "购买方名称: 甲公司\n销售方名称: 餐饮企业\n餐饮服务 金额 100.00 税额 6.00\n"
                + "价税合计(小写): ¥106.00";
        Invoice invoice = parser.parse(text, false);
        assertEquals("餐饮企业", invoice.issuer);
        assertEquals("甲公司", invoice.buyer);
        assertEquals(10600L, invoice.amountCents);
        assertEquals(Category.DINING, invoice.category);
        assertEquals(LocalDate.of(2026, 9, 7), invoice.issueDate);
        assertEquals(YearMonth.of(2026, 9), invoice.reimbursementMonth);
        assertEquals(ReviewStatus.READY, invoice.reviewStatus);
    }

    @Test void columnarInvoiceSeparatesBuyerAndSellerAndReadsSmallTotal() {
        String text = "电子发票（普通发票） 发票号码：11112222333344445555\n"
                + "开票日期：2026年09月12日\n"
                + "购 名称：测试购买方科技有限公司  销 名称：测试餐饮管理有限公司\n"
                + "*生产生活服务*餐饮费 441.51 税额 26.49\n"
                + "价税合计（大写）肆佰陆拾捌圆整 （小写）¥468.00";
        Invoice invoice = parser.parse(text, false);
        assertEquals("测试购买方科技有限公司", invoice.buyer);
        assertEquals("测试餐饮管理有限公司", invoice.issuer);
        assertEquals(46800L, invoice.amountCents);
        assertEquals(Category.DINING, invoice.category);
        assertEquals(LocalDate.of(2026, 9, 12), invoice.issueDate);
    }

    @Test void railwayInvoiceKeepsTravelDateSeparateFromIssueDate() {
        String text = "电子发票（铁路电子客票）\n发票号码：20260927000000000002\n"
                + "开票日期：2026年09月10日\n乘车日期：2026年09月08日\n旅客姓名：李四\n"
                + "销售方名称：中国铁路上海局集团有限公司\n票价：¥238.50";
        Invoice invoice = parser.parse(text, false);
        assertEquals(Category.TRANSPORT, invoice.category);
        assertEquals("李四", invoice.traveler);
        assertEquals(LocalDate.of(2026, 9, 10), invoice.issueDate);
        assertEquals(LocalDate.of(2026, 9, 8), invoice.travelDate);
        assertEquals(23850L, invoice.amountCents);
        assertEquals(ReviewStatus.READY, invoice.reviewStatus);
    }

    @Test void ticketFormWithoutIssueDateRequiresReview() {
        String text = "中国铁路 高铁 车次 G123\n乘车日期：2026年09月08日\n"
                + "乘车人：王五\n票价：¥518.00";
        Invoice invoice = parser.parse(text, true);
        assertEquals(Category.TRANSPORT, invoice.category);
        assertEquals(51800L, invoice.amountCents);
        assertNull(invoice.issueDate);
        assertEquals(ReviewStatus.NEEDS_REVIEW, invoice.reviewStatus);
    }

    @Test void taxOnlyAmountDoesNotBecomeInvoiceTotal() {
        Invoice invoice = parser.parse("开票日期: 2026-09-01\n销售方名称: 服务公司\n技术服务\n税额 6.00", false);
        assertNull(invoice.amountCents);
        assertEquals(ReviewStatus.NEEDS_REVIEW, invoice.reviewStatus);
    }

    @Test void ocrUsesOnlyUnambiguousAmountAndAlwaysRequiresReview() {
        Invoice clear = parser.parse("销售方名称: 餐饮公司\n开票日期: 2026-09-01\n餐饮服务\n从各合计 240.50", true);
        assertEquals(24050L, clear.amountCents);
        assertEquals(ReviewStatus.NEEDS_REVIEW, clear.reviewStatus);
        Invoice ambiguous = parser.parse("销售方名称: 餐饮公司\n餐饮服务\n金额 100.00\n税额 6.00", true);
        assertNull(ambiguous.amountCents);
    }

    @Test void ocrHandlesSpacedLabelsAndMultilineTaxInclusiveTotal() {
        Invoice invoice = parser.parse("销 售 方 名 称：测试餐饮管理有限公司\n"
                + "开票日期：2026年09月12日\n餐饮费\n价税合计（大写）\n肆佰陆拾捌圆整\n（小写）￥468.00", true);
        assertEquals("测试餐饮管理有限公司", invoice.issuer);
        assertEquals(46800L, invoice.amountCents);
    }

    @Test void flightItineraryUsesTotalRatherThanFareOrFees() {
        String text = "电子发票 航空运输电子客票行程单\n发标号码: 66667777888899990000\n"
                + "旅客姓名 有效身份证件号码 签注\n"
                + "自: 杭州 厦航 MF8033 2026年09月04日 08:05\n"
                + "票价 燃油附加费 增值税税额 民航发展基金 其他税费 合计\n"
                + "CNY 587.16 CNY 64.22 CNY 58.62 CNY 50.00 CNY 0.00 CNY 760.00\n"
                + "电子客票号码: 7312455982409\n"
                + "填开单位: 厦门航空有限公司 填开日期: 2026年09月26日\n"
                + "购买方名称: 某科技有限公司";
        Invoice invoice = parser.parse(text, true);
        assertEquals("厦门航空有限公司", invoice.issuer);
        assertEquals("某科技有限公司", invoice.buyer);
        assertNull(invoice.traveler);
        assertEquals("66667777888899990000", invoice.invoiceNumber);
        assertEquals(76000L, invoice.amountCents);
        assertEquals(LocalDate.of(2026, 9, 26), invoice.issueDate);
        assertEquals(LocalDate.of(2026, 9, 4), invoice.travelDate);
        assertEquals(Category.TRANSPORT, invoice.category);
        assertEquals(ReviewStatus.NEEDS_REVIEW, invoice.reviewStatus);
    }

    @Test void sellerRegionReadsOnlyACompanyName() {
        assertEquals("测试餐饮管理有限公司", InvoiceParser.sellerFromRegion(
                "名称: 测试餐饮管理有限公司\n统一社会信用代码/纳税人识别号: 123456789"));
        assertNull(InvoiceParser.sellerFromRegion("统一社会信用代码/纳税人识别号: 123456789"));
        assertNull(InvoiceParser.sellerFromRegion("名称: 购买方信息"));
    }
}
