package com.mod98.alpaca.tradingbot.Model;

import jakarta.persistence.Entity;
import jakarta.persistence.*;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "app_settings")
@Getter
@Setter
public class AppSettings {

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean regexEnabled;

    @Column(nullable = false)
    private boolean aiEnabled;

    @NotNull
    @DecimalMin(value = "0.01", message = "fixedBudget must be > 0")
    @Column(name = "fixed_budget", nullable = false, precision = 12, scale = 2)
    private BigDecimal fixedBudget;

    @NotNull
    @DecimalMin(value = "0.01", message = "tpPercent must be > 0")
    @DecimalMax(value = "100.00", message = "tpPercent must be <= 100")
    @Column(name = "tp_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal tpPercent;

    @NotNull
    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @NotBlank
    @Column(name = "session_dir", nullable = false, length = 255)
    private String sessionDir;

    @Column(name = "alpaca_paper", nullable = false)
    private boolean alpacaPaper = true;

    @Column(name = "alpaca_extended_hours", nullable = false)
    private boolean alpacaExtendedHours = true;

    @Column(name = "alpaca_max_slippage_bps", nullable = false)
    private Integer alpacaMaxSlippageBps = 30; // 30bps = 0.30%

    @Column(name = "alpaca_spread_guard_bps", nullable = false)
    private Integer alpacaSpreadGuardBps = 50; // 50bps = 0.50%

    @Column(name = "alpaca_min_volume")
    private Long alpacaMinVolume = 100000L;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
