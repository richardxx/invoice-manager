package com.localinvoice;

import java.time.LocalDate;
import java.time.YearMonth;

public class Invoice {
    public String id;
    public String originalPath;
    public String originalName;
    public String sha256;
    public String importedAt;
    public String issuer;
    public String buyer;
    public String traveler;
    public LocalDate travelDate;
    public String invoiceNumber;
    public LocalDate issueDate;
    public Long amountCents;
    public Category category;
    public YearMonth reimbursementMonth;
    public String ownerId;
    public ReviewStatus reviewStatus = ReviewStatus.NEEDS_REVIEW;
    public String duplicateOf;
    public String recognitionSource;
    public String manuallyEdited;
    public String intakeStatus = "IN_POOL";
    public String reimbursedAt;

    public boolean counted() {
        return ownerId != null && reimbursementMonth != null && amountCents != null
                && amountCents > 0 && category != null && reviewStatus == ReviewStatus.READY;
    }
}
