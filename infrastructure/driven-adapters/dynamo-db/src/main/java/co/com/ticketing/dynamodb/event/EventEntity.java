package co.com.ticketing.dynamodb.event;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.ticket.TicketStatus;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.time.Instant;

/**
 * Event item. Inventory counters live in the same item so a single conditional update can move tickets between
 * states atomically (e.g. {@code available >= :qty}).
 */
@DynamoDbBean
public class EventEntity {

    public static final String ENTITY_TYPE = "EVENT";
    public static final String UPCOMING_INDEX = "upcoming-events-index";
    public static final String ID = "id";
    public static final String VERSION = "version";

    private String id;
    private String entityType;
    private String name;
    private String venue;
    private Long startsAt;
    private Integer totalCapacity;
    private Integer available;
    private Integer reserved;
    private Integer pendingConfirmation;
    private Integer sold;
    private Integer complimentary;
    private Instant createdAt;
    private Long version;

    public static EventEntity from(Event event) {
        var entity = new EventEntity();
        var inventory = event.inventory();
        entity.setId(event.id());
        entity.setEntityType(ENTITY_TYPE);
        entity.setName(event.name());
        entity.setVenue(event.venue());
        entity.setStartsAt(event.startsAt().toEpochMilli());
        entity.setTotalCapacity(event.totalCapacity());
        entity.setAvailable(inventory.available());
        entity.setReserved(inventory.reserved());
        entity.setPendingConfirmation(inventory.pendingConfirmation());
        entity.setSold(inventory.sold());
        entity.setComplimentary(inventory.complimentary());
        entity.setCreatedAt(event.createdAt());
        entity.setVersion(event.version());
        return entity;
    }

    public Event toModel() {
        var inventory = new Inventory(available, reserved, pendingConfirmation, sold, complimentary);
        return new Event(id, name, venue, Instant.ofEpochMilli(startsAt), totalCapacity, inventory, createdAt,
                version == null ? 0L : version);
    }

    /** Name of the attribute that stores the counter of tickets in the given status. */
    public static String counterAttribute(TicketStatus status) {
        return switch (status) {
            case AVAILABLE -> "available";
            case RESERVED -> "reserved";
            case PENDING_CONFIRMATION -> "pendingConfirmation";
            case SOLD -> "sold";
            case COMPLIMENTARY -> "complimentary";
        };
    }

    @DynamoDbPartitionKey
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = UPCOMING_INDEX)
    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVenue() {
        return venue;
    }

    public void setVenue(String venue) {
        this.venue = venue;
    }

    @DynamoDbSecondarySortKey(indexNames = UPCOMING_INDEX)
    public Long getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(Long startsAt) {
        this.startsAt = startsAt;
    }

    public Integer getTotalCapacity() {
        return totalCapacity;
    }

    public void setTotalCapacity(Integer totalCapacity) {
        this.totalCapacity = totalCapacity;
    }

    public Integer getAvailable() {
        return available;
    }

    public void setAvailable(Integer available) {
        this.available = available;
    }

    public Integer getReserved() {
        return reserved;
    }

    public void setReserved(Integer reserved) {
        this.reserved = reserved;
    }

    public Integer getPendingConfirmation() {
        return pendingConfirmation;
    }

    public void setPendingConfirmation(Integer pendingConfirmation) {
        this.pendingConfirmation = pendingConfirmation;
    }

    public Integer getSold() {
        return sold;
    }

    public void setSold(Integer sold) {
        this.sold = sold;
    }

    public Integer getComplimentary() {
        return complimentary;
    }

    public void setComplimentary(Integer complimentary) {
        this.complimentary = complimentary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
