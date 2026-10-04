package co.com.ticketing.dynamodb.order;

import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

@DynamoDbBean
public class TicketEntity {

    private String id;
    private String status;

    public static TicketEntity from(Ticket ticket) {
        var entity = new TicketEntity();
        entity.setId(ticket.id());
        entity.setStatus(ticket.status().name());
        return entity;
    }

    public Ticket toModel() {
        return new Ticket(id, TicketStatus.valueOf(status));
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
