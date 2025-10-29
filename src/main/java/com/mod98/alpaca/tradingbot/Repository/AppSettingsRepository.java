package com.mod98.alpaca.tradingbot.Repository;

import com.mod98.alpaca.tradingbot.Model.AppSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingsRepository extends JpaRepository<AppSettings, Long> {


}
