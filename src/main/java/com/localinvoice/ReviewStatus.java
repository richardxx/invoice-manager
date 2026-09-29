package com.localinvoice;

public enum ReviewStatus {
    READY("就绪"), NEEDS_REVIEW("待核对"), POSSIBLE_DUPLICATE("疑似重复"), FAILED("失败");

    private final String label;

    ReviewStatus(String label) { this.label = label; }

    public String label() { return label; }

    @Override public String toString() { return label; }
}
