package com.localinvoice;

public record AiSettings(String baseUrl, String model, String apiKey) {
    public AiSettings {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        model = model == null ? "" : model.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean configured() {
        return !baseUrl.isBlank() && !model.isBlank() && !apiKey.isBlank();
    }
}
