package com.localinvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PoolPanelOrderTest {
    @Test void unassignedInvoicesComeFirstAndNewestFirstWithinEachGroup() {
        Invoice assignedNew = invoice("assigned-new", "owner", "2026-09-28T10:00:00Z");
        Invoice unassignedOld = invoice("unassigned-old", null, "2026-09-26T10:00:00Z");
        Invoice assignedOld = invoice("assigned-old", "owner", "2026-09-25T10:00:00Z");
        Invoice unassignedNew = invoice("unassigned-new", null, "2026-09-27T10:00:00Z");
        List<Invoice> invoices = new ArrayList<>(List.of(assignedNew, unassignedOld, assignedOld, unassignedNew));

        invoices.sort(PoolPanel.DISPLAY_ORDER);

        assertEquals(List.of("unassigned-new", "unassigned-old", "assigned-new", "assigned-old"),
                invoices.stream().map(invoice -> invoice.id).toList());
    }

    private static Invoice invoice(String id, String owner, String importedAt) {
        Invoice invoice = new Invoice();
        invoice.id = id;
        invoice.ownerId = owner;
        invoice.importedAt = importedAt;
        return invoice;
    }
}
