package com.localinvoice;

public enum Category {
    TRANSPORT("交通"), DINING("餐饮"), LODGING("住宿"), SERVICE("服务"), OTHER("其他");

    private final String label;

    Category(String label) { this.label = label; }

    public String label() { return label; }

    @Override public String toString() { return label; }
}
