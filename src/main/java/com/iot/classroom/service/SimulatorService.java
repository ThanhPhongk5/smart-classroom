package com.iot.classroom.service;

import com.iot.classroom.model.DeviceState;
import com.iot.classroom.repository.Repos;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.simulate", havingValue = "true")
public class SimulatorService {
    private final Repos.RoomRepo rooms;
    private final Repos.StateRepo states;
    private final AutomationService automation;
    private double temp = 29, lux = 450;

    public SimulatorService(Repos.RoomRepo rooms, Repos.StateRepo states, AutomationService automation) {
        this.rooms = rooms; this.states = states; this.automation = automation;
    }

    @Scheduled(fixedRate = 5000, initialDelay = 8000)
    public void run() {
        rooms.findByCode("A102").ifPresent(room -> {
            DeviceState s = states.findById(room.getId()).orElse(new DeviceState(room.getId()));
            temp += s.isAc() ? -0.4 : s.isFan() ? -0.1 : 0.1;
            temp = Math.max(24, Math.min(37, temp)) + (Math.random() - 0.5) * 0.2;
            lux = Math.max(80, Math.min(900, lux + (Math.random() - 0.5) * 60));
            boolean motion = Math.random() < 0.3;
            automation.ingest(room.getId(), Math.round(temp * 10) / 10.0, 60 + Math.random() * 10, Math.round(lux), motion);
        });
    }
}
