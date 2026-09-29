package com.localinvoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

public final class OpenAiVisionRecognition {
    private static final String PROMPT = "Read this Chinese invoice or train ticket. Return only one JSON object with "
            + "keys issuer, buyer, traveler, travelDate, invoiceNumber, issueDate, amount, category. "
            + "issuer means the seller/开票方/销售方, never the buyer/购买方. amount is the total tax-inclusive "
            + "CNY amount, not tax or subtotal. Dates use YYYY-MM-DD. category is one of TRANSPORT, DINING, "
            + "LODGING, SERVICE, OTHER. Use null when unreadable; do not invent values. "
            + "For train tickets, identify the railway issuer, traveler, travel date, ticket amount and TRANSPORT.";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    public OpenAiVisionRecognition() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    OpenAiVisionRecognition(HttpClient http) { this.http = http; }

    public Invoice recognize(Path pdf, AiSettings settings) throws Exception {
        return recognize(pdf, settings, ignored -> { });
    }

    public Invoice recognize(Path pdf, AiSettings settings, Consumer<String> progress) throws Exception {
        SettingsService.validate(settings);
        progress.accept("准备发票图像");
        List<Object> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", PROMPT));
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            if (document.getNumberOfPages() == 0 || document.getNumberOfPages() > 25)
                throw new IllegalArgumentException("PDF页数须为1至25页");
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < Math.min(2, document.getNumberOfPages()); page++) {
                BufferedImage rendered = renderer.renderImageWithDPI(page, 160, ImageType.RGB);
                BufferedImage rgb = new BufferedImage(rendered.getWidth(), rendered.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = rgb.createGraphics();
                graphics.drawImage(rendered, 0, 0, null);
                graphics.dispose();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ImageIO.write(rgb, "jpeg", bytes);
                rendered.flush();
                rgb.flush();
                String data = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", data, "detail", "high")));
            }
        }
        Map<String, Object> request = Map.of("model", settings.model(),
                "messages", List.of(Map.of("role", "user", "content", content)));
        String base = settings.baseUrl().replaceAll("/+$", "");
        HttpRequest call = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .header("Authorization", "Bearer " + settings.apiKey())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build();
        progress.accept("请求模型");
        HttpResponse<String> response = http.send(call, HttpResponse.BodyHandlers.ofString());
        progress.accept("获取返回");
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("AI 服务返回 HTTP " + response.statusCode() + "：" + response.body());
        JsonNode message = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
        if (!message.isTextual()) throw new IllegalStateException("AI 服务未返回可解析的识别结果");
        String answer = message.asText().trim();
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("AI 服务未返回 JSON 对象");
        JsonNode data = json.readTree(answer.substring(start, end + 1));
        Invoice invoice = new Invoice();
        invoice.issuer = value(data, "issuer");
        invoice.buyer = value(data, "buyer");
        invoice.traveler = value(data, "traveler");
        invoice.travelDate = date(value(data, "travelDate"));
        invoice.invoiceNumber = value(data, "invoiceNumber");
        invoice.issueDate = date(value(data, "issueDate"));
        invoice.amountCents = amount(value(data, "amount"));
        invoice.category = category(value(data, "category"));
        invoice.reimbursementMonth = null;
        invoice.reviewStatus = ReviewStatus.NEEDS_REVIEW;
        invoice.recognitionSource = "AI";
        return invoice;
    }

    private static String value(JsonNode data, String key) {
        JsonNode node = data.path(key);
        if (node.isMissingNode() || node.isNull()) return null;
        String value = node.asText().trim();
        return value.isBlank() || value.equalsIgnoreCase("null") ? null : value;
    }

    private static LocalDate date(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replace('年', '-').replace('月', '-').replace("日", "").replace('/', '-').trim();
        try {
            String[] parts = cleaned.split("-");
            if (parts.length != 3) return null;
            return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (Exception invalid) { return null; }
    }

    private static Long amount(String raw) {
        if (raw == null) return null;
        try {
            String cleaned = raw.replace("¥", "").replace("￥", "").replace("元", "")
                    .replace(",", "").trim();
            BigDecimal cents = new BigDecimal(cleaned).movePointRight(2);
            long value = cents.longValueExact();
            return value > 0 && value <= 100_000_000_000L ? value : null;
        } catch (Exception invalid) { return null; }
    }

    private static Category category(String raw) {
        if (raw == null) return Category.OTHER;
        String normalized = raw.toUpperCase(Locale.ROOT);
        try { return Category.valueOf(normalized); }
        catch (IllegalArgumentException ignored) {
            for (Category category : Category.values()) {
                if (category.label().equals(raw)) return category;
            }
            return Category.OTHER;
        }
    }
}
