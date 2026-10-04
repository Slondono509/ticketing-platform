package co.com.ticketing.api.dto;

/**
 * @param paymentToken token issued by the payment provider's client SDK; card data never reaches this API
 */
public record ConfirmOrderRequest(String paymentToken) {
}
