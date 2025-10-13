package com.mod98.alpaca.tradingbot.Repository;

import com.mod98.alpaca.tradingbot.Model.TradeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TradeEventRepository extends JpaRepository<TradeEvent, Long> {

}
