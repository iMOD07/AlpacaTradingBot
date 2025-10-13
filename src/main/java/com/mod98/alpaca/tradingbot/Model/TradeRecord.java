package com.mod98.alpaca.tradingbot.Model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trade_records")
@Getter
@Setter
public class TradeRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(precision = 12, scale = 3)
    private BigDecimal trigger;

    @Column(name = "stop_loss", precision = 12, scale = 3)
    private BigDecimal stopLoss;

    @Column(name = "entry_price", precision = 12, scale = 3)
    private BigDecimal entryPrice;

    @Column(name = "exit_price", precision = 12, scale = 3)
    private BigDecimal exitPrice;

    private Integer qty;

    @Column(length = 50)
    private String buyOrderId;

    @Column(length = 20)
    private String state;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant closedAt;

}
