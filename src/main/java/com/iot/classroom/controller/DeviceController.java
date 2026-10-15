package com.iot.classroom.controller;

import com.iot.classroom.model.*;
import com.iot.classroom.repository.Repos;
import com.iot.classroom.service.AutomationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

@RestController @RequestMapping("/api/device")
public class DeviceController {
    private final AutomationService automation;
    private final Repos.RoomRepo rooms;
    private final Repos.BehaviorRepo behaviorRepo;
    private final Repos.SeatRepo seatRepo;
    private final Repos.LogRepo logs;
    @Value("${app.device-key}") private String deviceKey;

    public DeviceController(AutomationService automation, Repos.RoomRepo rooms,
                            Repos.BehaviorRepo behaviorRepo, Repos.SeatRepo seatRepo,
                            Repos.LogRepo logs) {
        this.automation = automation;
        this.rooms = rooms;
        this.behaviorRepo = behaviorRepo;
        this.seatRepo = seatRepo;
        this.logs = logs;
    }

    public record Telemetry(String roomCode, double temperature, double humidity, double lux, boolean motion) {}

    @PostMapping("/telemetry")
    public ResponseEntity<?> telemetry(@RequestHeader(value = "X-Device-Key", required = false) String key, @RequestBody Telemetry t) {
        if (!deviceKey.equals(key)) return ResponseEntity.status(401).body(Map.of("message", "Sai device key"));
        Room room = rooms.findByCode(t.roomCode()).orElse(null);
        if (room == null) return ResponseEntity.status(404).body(Map.of("message", "Khong co phong " + t.roomCode()));
        DeviceState s = automation.ingest(room.getId(), t.temperature(), t.humidity(), t.lux(), t.motion());
        return ResponseEntity.ok(Map.of("light", s.isLight(), "fan", s.isFan(), "ac", s.isAc(), "acTemp", s.getAcTemp(), "auto", s.isAuto()));
    }

    public record BehaviorTelemetry(
        String roomCode,
        int totalStudents,
        int handRaising,
        int reading,
        int writing,
        int usingPhone,
        int bowingHead,
        int leaningTable
    ) {}

    @PostMapping("/behavior")
    public ResponseEntity<?> behavior(
            @RequestHeader(value = "X-Device-Key", required = false) String key,
            @RequestBody BehaviorTelemetry b) {
        if (!deviceKey.equals(key)) return ResponseEntity.status(401).body(Map.of("message", "Sai device key"));
        Room room = rooms.findByCode(b.roomCode()).orElse(null);
        if (room == null) return ResponseEntity.status(404).body(Map.of("message", "Khong co phong " + b.roomCode()));

        BehaviorLog log = new BehaviorLog(room.getId(), b.totalStudents(), b.handRaising(), b.reading(),
                b.writing(), b.usingPhone(), b.bowingHead(), b.leaningTable());
        behaviorRepo.save(log);

        if (b.totalStudents() > 0) {
            automation.recordPresence(room.getId());
        }

        return ResponseEntity.ok(Map.of(
            "status", "success",
            "roomId", room.getId(),
            "attentionScore", log.getAttentionScore(),
            "totalStudents", log.getTotalStudents()
        ));
    }

    public record SeatUpdate(String seatCode, String status, String action) {}
    public record SeatTelemetry(String roomCode, List<SeatUpdate> updates) {}

    @PostMapping("/seats")
    public ResponseEntity<?> updateSeats(
            @RequestHeader(value = "X-Device-Key", required = false) String key,
            @RequestBody SeatTelemetry st) {
        if (!deviceKey.equals(key)) return ResponseEntity.status(401).body(Map.of("message", "Sai device key"));
        Room room = rooms.findByCode(st.roomCode()).orElse(null);
        if (room == null) return ResponseEntity.status(404).body(Map.of("message", "Khong co phong " + st.roomCode()));

        for (SeatUpdate u : st.updates()) {
            seatRepo.findByRoomIdAndSeatCode(room.getId(), u.seatCode()).ifPresent(seat -> {
                seat.setCurrentStatus(u.status());
                seat.setCurrentAction(u.action());
                seat.setLastSeen(LocalDateTime.now());
                if ("using_phone".equals(u.status())) {
                    seat.setViolationCount(seat.getViolationCount() + 1);
                    logs.save(new ActionLog(room.getId(), "[CẢNH BÁO AI] " + seat.getStudentName() + " (" + seat.getSeatCode() + ") dùng điện thoại", "AI_CAMERA"));
                } else if ("raising_hand".equals(u.status())) {
                    seat.setParticipationCount(seat.getParticipationCount() + 1);
                }
                seatRepo.save(seat);
            });
        }
        return ResponseEntity.ok(Map.of("status", "success"));
    }
}
