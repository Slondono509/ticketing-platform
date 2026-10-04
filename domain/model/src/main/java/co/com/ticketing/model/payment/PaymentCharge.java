package co.com.ticketing.model.payment;

/**
 * @param orderId      also used as idempotency key with the payment provider, so a redelivered message never charges twice
 * @param paymentToken tokenized payment method; card data never reaches this service
 */
public record PaymentCharge(String orderId, String customerId, int quantity, String paymentToken) {
}
