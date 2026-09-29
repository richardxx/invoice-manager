package com.localinvoice;

public record Person(String id, String name, String avatarPath, boolean company, PaymentDetails payment) {
    public Person { payment = payment == null ? PaymentDetails.empty() : payment; }
    public Person(String id, String name, String avatarPath, boolean company) {
        this(id, name, avatarPath, company, PaymentDetails.empty());
    }
    @Override public String toString() { return name; }
}
