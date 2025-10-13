package com.mod98.alpaca.tradingbot.Model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "trade_events")
@Getter @Setter
public class TradeEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 20)
    private String symbol;

    @Column(name = "event_type", length = 40, nullable = false)
    private String eventType;

    @Column(length = 200)
    private String message;

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Lob
    @Column(name = "payload_json")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
