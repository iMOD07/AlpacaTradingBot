package com.mod98.alpaca.tradingbot.Controller;

import com.mod98.alpaca.tradingbot.Service.SettingsService;
import com.mod98.alpaca.tradingbot.Model.AppSettings;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    // GET settings (from smart cache or DB)
    @GetMapping
    public AppSettings get() {
        return service.get();
    }

    // Update Settings (and Cache)
    @PutMapping
    public AppSettings put(@Valid @RequestBody AppSettings req) {
        req.setId(1L);
        return service.update(req);
    }


}
