package com.localinvoice;

public record ImportResult(String filename, Invoice invoice, String message, boolean success) {}
