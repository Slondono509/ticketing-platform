package co.com.ticketing.model.payment;

public sealed interface PaymentResult {

    record Approved(String reference) implements PaymentResult {
    }

    record Declined(String reason) implements PaymentResult {
    }
}
