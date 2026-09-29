package com.localinvoice;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

class OpenAiVisionRecognitionTest {
    @TempDir Path temp;

    @Test void sendsInvoiceImageAndParsesSellerTotalAndTicketFields() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String answer = json.writeValueAsString(Map.of("issuer", "中国铁路某局", "buyer", "某公司",
                    "traveler", "张三", "travelDate", "2026-09-21", "invoiceNumber", "123456",
                    "issueDate", "2026-09-20", "amount", "128.40", "category", "TRANSPORT"));
            byte[] response = json.writeValueAsBytes(Map.of("choices", new Object[]{Map.of("message",
                    Map.of("content", answer))}));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        try {
            Path pdf = temp.resolve("ticket.pdf");
            try (PDDocument document = new PDDocument()) {
                document.addPage(new PDPage());
                document.save(pdf.toFile());
            }
            AiSettings settings = new AiSettings("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/v1", "vision-test", "secret-test-key");
            List<String> phases = new ArrayList<>();
            Invoice invoice = new OpenAiVisionRecognition().recognize(pdf, settings, phases::add);
            assertEquals(List.of("准备发票图像", "请求模型", "获取返回"), phases);
            assertEquals("Bearer secret-test-key", authorization.get());
            JsonNode sent = json.readTree(requestBody.get());
            assertEquals("vision-test", sent.path("model").asText());
            assertTrue(sent.path("messages").path(0).path("content").path(1)
                    .path("image_url").path("url").asText().startsWith("data:image/jpeg;base64,"));
            assertEquals("中国铁路某局", invoice.issuer);
            assertEquals("某公司", invoice.buyer);
            assertEquals(12840L, invoice.amountCents);
            assertEquals(LocalDate.of(2026, 9, 20), invoice.issueDate);
            assertEquals(Category.TRANSPORT, invoice.category);
            assertEquals("张三", invoice.traveler);
            assertNull(invoice.reimbursementMonth);
            assertEquals(ReviewStatus.NEEDS_REVIEW, invoice.reviewStatus);
        } finally { server.stop(0); }
    }

    @Test void rejectsUnencryptedRemoteEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> SettingsService.validate(
                new AiSettings("http://example.com/v1", "vision", "secret")));
    }

    @Test void realInvoiceIsRenderedForTheConfiguredVisionEndpoint() throws Exception {
        String encoded = System.getProperty("invoice.sample.path.b64");
        Assumptions.assumeTrue(encoded != null);
        Path sample = Path.of(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        Assumptions.assumeTrue(Files.isRegularFile(sample));
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String answer = "{\"issuer\":\"测试餐饮企业\",\"amount\":\"468.00\","
                    + "\"issueDate\":\"2026-09-12\",\"category\":\"DINING\"}";
            byte[] response = new ObjectMapper().writeValueAsBytes(Map.of("choices",
                    new Object[]{Map.of("message", Map.of("content", answer))}));
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        try {
            AiSettings settings = new AiSettings("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1", "vision-test", "secret");
            Invoice recognized = new OpenAiVisionRecognition().recognize(sample, settings);
            assertEquals(46800L, recognized.amountCents);
            JsonNode sent = new ObjectMapper().readTree(requestBody.get());
            String imageUrl = sent.path("messages").path(0).path("content").path(1)
                    .path("image_url").path("url").asText();
            assertTrue(imageUrl.startsWith("data:image/jpeg;base64,"));
            assertTrue(imageUrl.length() > 20_000);
        } finally { server.stop(0); }
    }
}
