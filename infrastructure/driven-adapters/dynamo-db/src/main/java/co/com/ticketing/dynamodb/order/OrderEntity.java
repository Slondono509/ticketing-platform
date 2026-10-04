package co.com.ticketing.dynamodb.order;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderType;
import co.com.ticketing.model.ticket.Ticket;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.time.Instant;
import java.util.List;

/**
 * Order item. {@code expirationShard} and {@code expiresAt} are only written while the order is RESERVED, which makes
 * {@link #EXPIRATION_INDEX} a sparse index that contains nothing but live reservations. The shard spreads those
 * writes across several index partitions to avoid a hot key during on-sales.
 */
@DynamoDbBean
public class OrderEntity {

    public static final String EXPIRATION_INDEX = "reservation-expiration-index";
    public static final String ID = "id";
    public static final String STATUS = "status";
    public static final String VERSION = "version";

    private String id;
    private String eventId;
    private String customerId;
    private Integer quantity;
    private String type;
    private String status;
    private List<TicketEntity> tickets;
    private String expirationShard;
    private Long expiresAt;
    private String statusReason;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;

    public static OrderEntity from(Order order, int shards) {
        var entity = new OrderEntity();
        entity.setId(order.id());
        entity.setEventId(order.eventId());
        entity.setCustomerId(order.customerId());
        entity.setQuantity(order.quantity());
        entity.setType(order.type().name());
        entity.setStatus(order.status().name());
        entity.setTickets(order.tickets().stream().map(TicketEntity::from).toList());
        if (order.status() == OrderStatus.RESERVED && order.expiresAt() != null) {
            entity.setExpirationShard(shardOf(order.id(), shards));
            entity.setExpiresAt(order.expiresAt().toEpochMilli());
        }
        entity.setStatusReason(order.statusReason());
        entity.setCreatedAt(order.createdAt());
        entity.setUpdatedAt(order.updatedAt());
        entity.setVersion(order.version());
        return entity;
    }

    public Order toModel() {
        var ticketModels = tickets == null ? List.<Ticket>of()
                : tickets.stream().map(TicketEntity::toModel).toList();
        return new Order(id, eventId, customerId, quantity, OrderType.valueOf(type), OrderStatus.valueOf(status),
                ticketModels, expiresAt == null ? null : Instant.ofEpochMilli(expiresAt), statusReason, createdAt,
                updatedAt, version == null ? 0L : version);
    }

    public static String shardOf(String orderId, int shards) {
        return "SHARD#" + Math.floorMod(orderId.hashCode(), shards);
    }

    public static String shard(int number) {
        return "SHARD#" + number;
    }

    @DynamoDbPartitionKey
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<TicketEntity> getTickets() {
        return tickets;
    }

    public void setTickets(List<TicketEntity> tickets) {
        this.tickets = tickets;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = EXPIRATION_INDEX)
    public String getExpirationShard() {
        return expirationShard;
    }

    public void setExpirationShard(String expirationShard) {
        this.expirationShard = expirationShard;
    }

    @DynamoDbSecondarySortKey(indexNames = EXPIRATION_INDEX)
    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
