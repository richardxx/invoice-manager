package com.localinvoice;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import net.sourceforge.tess4j.Tesseract;

public final class RecognitionService {
    private final InvoiceParser parser = new InvoiceParser();
    private final Path tessdata;

    public RecognitionService(Path projectRoot) {
        this.tessdata = findTessdata(projectRoot);
    }

    public Invoice recognize(Path pdf) throws Exception {
        return recognize(pdf, false);
    }

    public Invoice recognize(Path pdf, boolean forceOcr) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            if (document.getNumberOfPages() == 0 || document.getNumberOfPages() > 25)
                throw new IllegalArgumentException("PDF页数须为1至25页");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            Invoice extracted = parser.parse(text, false);
            boolean sellerUnreliable = unreliableSeller(extracted);
            if (!forceOcr && !sellerUnreliable && extracted.amountCents != null && extracted.issueDate != null
                    && extracted.category != Category.OTHER) return extracted;
            if (tessdata == null) {
                if (forceOcr) throw new IllegalStateException("OCR 语言数据不可用，请检查 tessdata 目录");
                return extracted;
            }
            try {
                StringBuilder ocrText = new StringBuilder(text).append('\n');
                PDFRenderer renderer = new PDFRenderer(document);
                Tesseract ocr = new Tesseract();
                ocr.setDatapath(tessdata.toString());
                ocr.setLanguage("chi_sim+eng");
                String regionSeller = null;
                for (int page = 0; page < Math.min(document.getNumberOfPages(), 2); page++) {
                    BufferedImage image = renderer.renderImageWithDPI(page, 300, ImageType.GRAY);
                    try {
                        String standardText = ocr.doOCR(image);
                        if (looksLikeAirItinerary(text + standardText)) {
                            ocr.setPageSegMode(6);
                            ocrText.append(ocr.doOCR(image)).append('\n');
                            ocr.setPageSegMode(3);
                        }
                        ocrText.append(standardText).append('\n');
                        if (page == 0 && (sellerUnreliable || forceOcr)
                                && looksLikeStandardInvoice(text + standardText)) {
                            try { regionSeller = sellerFromRegion(image, ocr); }
                            catch (Exception ignored) { /* Full-page OCR remains available. */ }
                        }
                    } finally {
                        image.flush();
                    }
                }
                Invoice combined = parser.parse(ocrText.toString(), true);
                if (regionSeller != null && (forceOcr || sellerUnreliable)
                        && !regionSeller.equals(extracted.buyer) && !regionSeller.equals(combined.buyer))
                    combined.issuer = regionSeller;
                else if (!sellerUnreliable) combined.issuer = extracted.issuer;
                else if (combined.issuer == null) combined.issuer = extracted.issuer;
                if (extracted.amountCents != null) combined.amountCents = extracted.amountCents;
                if (extracted.issueDate != null) combined.issueDate = extracted.issueDate;
                if (extracted.category != Category.OTHER) combined.category = extracted.category;
                if (combined.invoiceNumber == null) combined.invoiceNumber = extracted.invoiceNumber;
                if (combined.buyer == null) combined.buyer = extracted.buyer;
                if (combined.traveler == null) combined.traveler = extracted.traveler;
                if (combined.travelDate == null) combined.travelDate = extracted.travelDate;
                if (combined.issueDate != null) combined.reimbursementMonth = java.time.YearMonth.from(combined.issueDate);
                combined.reviewStatus = ReviewStatus.NEEDS_REVIEW;
                return combined;
            } catch (Exception | UnsatisfiedLinkError error) {
                if (forceOcr) throw new IllegalStateException("OCR 识别失败：" + error.getMessage(), error);
                extracted.reviewStatus = ReviewStatus.NEEDS_REVIEW;
                extracted.recognitionSource = "OCR_UNAVAILABLE";
                return extracted;
            }
        }
    }

    public boolean ocrAvailable() { return tessdata != null; }

    private static boolean unreliableSeller(Invoice invoice) {
        String seller = invoice.issuer;
        return seller == null || seller.isBlank() || seller.equals(invoice.buyer)
                || seller.equals("信息") || seller.contains("名称") || seller.startsWith("销售方信息")
                || seller.contains("纳税人识别号") || seller.contains("统一社会信用代码");
    }

    private static boolean looksLikeStandardInvoice(String text) {
        if (looksLikeAirItinerary(text)) return false;
        int names = occurrences(text, "名称");
        int taxIds = occurrences(text, "纳税人识别号");
        return text.contains("销售方信息") || names >= 2 && (taxIds >= 2 || text.contains("价税合计"));
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int index = text.indexOf(token); index >= 0; index = text.indexOf(token, index + token.length())) count++;
        return count;
    }

    private String sellerFromRegion(BufferedImage image, Tesseract ocr) throws Exception {
        if (image.getWidth() <= image.getHeight()) return null;
        double[][] regions = {{0.52, 0.16, 0.45, 0.15}, {0.535, 0.175, 0.34, 0.065}};
        for (double[] region : regions) {
            int x = (int) (image.getWidth() * region[0]);
            int y = (int) (image.getHeight() * region[1]);
            int width = (int) (image.getWidth() * region[2]);
            int height = (int) (image.getHeight() * region[3]);
            String seller = InvoiceParser.sellerFromRegion(ocr.doOCR(image.getSubimage(x, y, width, height)));
            if (seller != null) return seller;
        }
        return null;
    }

    private static boolean looksLikeAirItinerary(String text) {
        return text.contains("电子客票号码") || text.contains("民航发展基金")
                || text.contains("航空运输电子客票") || text.contains("燃油附加费");
    }

    private static Path findTessdata(Path projectRoot) {
        String override = System.getProperty("invoice.tessdata");
        if (override != null && Files.isRegularFile(Path.of(override, "chi_sim.traineddata"))) return Path.of(override);
        Path local = projectRoot.resolve("tessdata");
        if (Files.isRegularFile(local.resolve("chi_sim.traineddata"))) return local;
        try {
            CodeSource source = RecognitionService.class.getProtectionDomain().getCodeSource();
            Path jarDir = Path.of(source.getLocation().toURI()).toAbsolutePath().getParent();
            Path bundled = jarDir.resolve("tessdata");
            if (Files.isRegularFile(bundled.resolve("chi_sim.traineddata"))) return bundled;
        } catch (Exception ignored) {
            // The application still accepts PDFs for manual review when OCR resources are absent.
        }
        return null;
    }
}
