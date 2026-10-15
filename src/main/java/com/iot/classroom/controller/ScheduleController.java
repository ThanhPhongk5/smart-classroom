package com.iot.classroom.controller;

import com.iot.classroom.model.ActionLog;
import com.iot.classroom.model.Schedule;
import com.iot.classroom.repository.Repos;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/schedules")
public class ScheduleController {
    private final Repos.ScheduleRepo repo;
    private final Repos.LogRepo logs;

    public ScheduleController(Repos.ScheduleRepo repo, Repos.LogRepo logs) {
        this.repo = repo; this.logs = logs;
    }

    @GetMapping
    public List<Schedule> list(@RequestParam Long roomId) { return repo.findByRoomIdOrderByDayOfWeekAscStartTimeAsc(roomId); }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Schedule s, Authentication a) {
        if (s.getRoomId() == null || s.getStartTime() == null || s.getEndTime() == null || s.getSubject() == null || s.getSubject().isBlank())
            return bad(400, "Vui long nhap du mon hoc, gio bat dau va gio ket thuc");
        if (s.getDayOfWeek() < 1 || s.getDayOfWeek() > 7) return bad(400, "Thu khong hop le");
        if (!s.getStartTime().isBefore(s.getEndTime())) return bad(400, "Gio ket thuc phai sau gio bat dau");
        for (Schedule o : repo.findByRoomIdAndDayOfWeek(s.getRoomId(), s.getDayOfWeek()))
            if (s.getStartTime().isBefore(o.getEndTime()) && o.getStartTime().isBefore(s.getEndTime()))
                return bad(409, "Trung lich voi '" + o.getSubject() + "' (" + o.getStartTime() + "-" + o.getEndTime() + ")");
        s.setId(null);
        Schedule saved = repo.save(s);
        logs.save(new ActionLog(s.getRoomId(), "Them lich: " + s.getSubject(), a.getName()));
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication a) {
        repo.findById(id).ifPresent(s -> { repo.delete(s); logs.save(new ActionLog(s.getRoomId(), "Xoa lich: " + s.getSubject(), a.getName())); });
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> bad(int code, String msg) { return ResponseEntity.status(code).body(Map.of("message", msg)); }
}
