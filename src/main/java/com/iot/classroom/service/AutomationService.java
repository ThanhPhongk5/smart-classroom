package com.iot.classroom.service;

import com.iot.classroom.model.*;
import com.iot.classroom.repository.Repos;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class AutomationService {
    private final Repos.RoomRepo rooms;
    private final Repos.ReadingRepo readings;
    private final Repos.StateRepo states;
    private final Repos.ScheduleRepo schedules;
    private final Repos.LogRepo logs;

    public AutomationService(Repos.RoomRepo rooms, Repos.ReadingRepo readings,
                             Repos.StateRepo states, Repos.ScheduleRepo schedules,
                             Repos.LogRepo logs) {
        this.rooms = rooms; this.readings = readings; this.states = states;
        this.schedules = schedules; this.logs = logs;
    }

    public List<String> getAlerts(Long roomId) {
        List<String> list = new ArrayList<>();
        SensorReading r = readings.findFirstByRoomIdOrderByCreatedAtDesc(roomId).orElse(null);
        if (r != null) {
            if (r.getTemperature() > 33) list.add("Nhiệt độ phòng cao vượt ngưỡng (" + r.getTemperature() + "°C)");
            if (r.getLux() < 200) list.add("Cường độ ánh sáng yếu (" + r.getLux() + " lux)");
        }
        return list;
    }

    public Optional<Schedule> currentClass(Long roomId) {
        LocalDateTime n = LocalDateTime.now();
        LocalTime t = n.toLocalTime();
        return schedules.findByRoomIdAndDayOfWeek(roomId, n.getDayOfWeek().getValue()).stream()
                .filter(s -> !t.isBefore(s.getStartTime()) && t.isBefore(s.getEndTime())).findFirst();
    }

    public DeviceState ingest(Long roomId, double temp, double hum, double lux, boolean motion) {
        SensorReading r = new SensorReading();
        r.setRoomId(roomId); r.setTemperature(temp); r.setHumidity(hum); r.setLux(lux); r.setMotion(motion);
        readings.save(r);
        DeviceState s = states.findById(roomId).orElseGet(() -> new DeviceState(roomId));
        s.setLastSeen(LocalDateTime.now());
        if (motion) s.setLastMotionAt(LocalDateTime.now());
        states.save(s);
        apply(roomId);
        return states.findById(roomId).get();
    }

    public void recordPresence(Long roomId) {
        DeviceState s = states.findById(roomId).orElseGet(() -> new DeviceState(roomId));
        s.setLastMotionAt(LocalDateTime.now());
        states.save(s);
        apply(roomId);
    }

    @Scheduled(fixedRate = 30_000)
    public void tick() { rooms.findAll().forEach(r -> apply(r.getId())); }

    @Scheduled(cron = "0 0 3 * * *")
    public void cleanup() { readings.deleteByCreatedAtBefore(LocalDateTime.now().minusDays(30)); }

    public void apply(Long roomId) {
        DeviceState s = states.findById(roomId).orElse(null);
        if (s == null || !s.isAuto()) return;
        SensorReading r = readings.findFirstByRoomIdOrderByCreatedAtDesc(roomId).orElse(null);

        // Doc du lieu moi truong thuc te tu cam bien ESP32 (DHT11 va Quang tro LDR)
        double t = r != null ? r.getTemperature() : 26.5;
        double lx = r != null ? r.getLux() : 500.0;

        // 1. Tu dong bat Den neu anh sang yeu / phong toi (< 300 lux)
        boolean light = lx < 300.0;

        // 2. Tu dong dieu khien lam mat (Dieu hoa & Quat thong gio) theo nhiet do phong:
        // - Khi rat nong (>= 30°C hoac dang chay va >= 28°C): Bat Dieu hoa, tat Quat
        // - Khi nong vua (27°C - 30°C): Bat Quat thong gio, tat Dieu hoa
        // - Khi mat me (< 27°C): Tat ca hai de tiet kiem dien
        boolean ac = t >= 30.0 || (s.isAc() && t >= 28.0);
        boolean fan = t >= 27.0 && !ac;

        String why = String.format("Tự động theo cảm biến: Nhiệt độ %.1f°C, Ánh sáng %.0f lux", t, lx);
        change(s, "Đèn", s.isLight(), light, why);
        change(s, "Quạt", s.isFan(), fan, why);
        change(s, "Điều hòa", s.isAc(), ac, why);
        s.setLight(light); s.setFan(fan); s.setAc(ac);
        states.save(s);
    }

    private void change(DeviceState s, String name, boolean old, boolean now, String why) {
        if (old != now) logs.save(new ActionLog(s.getRoomId(), name + (now ? " BẬT" : " TẮT") + " (" + why + ")", "AUTO"));
    }
}
