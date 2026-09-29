package com.localinvoice;

import java.util.ArrayList;
import java.util.List;

public record PaymentDetails(String accountNumber, String accountName, BankType bankType,
                             String cnapsNumber, String identityType, String identityNumber) {
    public static final List<String> IDENTITY_TYPES = List.of("居民身份证", "中华人民共和国因私护照",
            "外国护照", "港澳居民来往内地通行证（香港）");

    public enum BankType {
        OTHER("他行"), BOC("中行");

        private final String label;
        BankType(String label) { this.label = label; }
        public String label() { return label; }
        @Override public String toString() { return label; }
    }

    public PaymentDetails {
        accountNumber = clean(accountNumber);
        accountName = clean(accountName);
        cnapsNumber = clean(cnapsNumber);
        identityType = clean(identityType);
        if ("身份证".equals(identityType)) identityType = IDENTITY_TYPES.getFirst();
        identityNumber = clean(identityNumber);
    }

    public static PaymentDetails empty() { return new PaymentDetails("", "", null, "", "", ""); }

    public List<String> missingFields() {
        List<String> missing = new ArrayList<>();
        if (accountNumber.isBlank()) missing.add("卡号/账号");
        if (accountName.isBlank()) missing.add("户名");
        if (bankType == null) missing.add("收款行类型");
        if (cnapsNumber.isBlank()) missing.add("收款行CNAPS号");
        if (!IDENTITY_TYPES.contains(identityType)) missing.add("证件类型（请从列表选择）");
        if (identityNumber.isBlank()) missing.add("证件号码");
        return missing;
    }

    public PaymentDetails requireComplete() {
        List<String> missing = missingFields();
        if (!missing.isEmpty()) throw new IllegalArgumentException("请填写：" + String.join("、", missing));
        return this;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
