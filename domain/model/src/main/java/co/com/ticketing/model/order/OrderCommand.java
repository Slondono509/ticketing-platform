package co.com.ticketing.model.order;

/**
 * Asynchronous instruction published to the order queue and executed by the order consumer.
 *
 * @param paymentToken tokenized payment method, only present for {@link OrderAction#PROCESS_PAYMENT}
 */
public record OrderCommand(String orderId, OrderAction action, String paymentToken) {

    public static OrderCommand reserve(String orderId) {
        return new OrderCommand(orderId, OrderAction.RESERVE, null);
    }

    public static OrderCommand grantComplimentary(String orderId) {
        return new OrderCommand(orderId, OrderAction.GRANT_COMPLIMENTARY, null);
    }

    public static OrderCommand processPayment(String orderId, String paymentToken) {
        return new OrderCommand(orderId, OrderAction.PROCESS_PAYMENT, paymentToken);
    }

    public static OrderCommand initialFor(Order order) {
        return switch (order.type()) {
            case PURCHASE -> reserve(order.id());
            case COMPLIMENTARY -> grantComplimentary(order.id());
        };
    }
}
