package com.iot.classroom.controller;

import com.iot.classroom.model.*;
import com.iot.classroom.repository.Repos;
import com.iot.classroom.service.AutomationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController @RequestMapping("/api")
public class RoomController {
    private final Repos.RoomRepo rooms;
    private final Repos.ReadingRepo readings;
    private final Repos.StateRepo states;
    private final Repos.LogRepo logs;
    private final Repos.UserRepo users;
    private final Repos.BehaviorRepo behaviorRepo;
    private final Repos.SeatRepo seatRepo;
    private final AutomationService automation;

    public RoomController(Repos.RoomRepo rooms, Repos.ReadingRepo readings,
                          Repos.StateRepo states, Repos.LogRepo logs,
                          Repos.UserRepo users, Repos.BehaviorRepo behaviorRepo,
                          Repos.SeatRepo seatRepo, AutomationService automation) {
        this.rooms = rooms; this.readings = readings; this.states = states;
        this.logs = logs; this.users = users; this.behaviorRepo = behaviorRepo;
        this.seatRepo = seatRepo; this.automation = automation;
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication a) {
        AppUser u = users.findByUsername(a.getName()).orElseThrow();
        return Map.of("username", u.getUsername(), "fullName", u.getFullName(), "role", u.getRole());
    }

    @GetMapping("/rooms")
    public List<Room> rooms() { return rooms.findAll(); }

    @GetMapping("/rooms/{id}/status")
    public Map<String, Object> status(@PathVariable Long id) {
        DeviceState s = states.findById(id).orElse(new DeviceState(id));
        SensorReading r = readings.findFirstByRoomIdOrderByCreatedAtDesc(id).orElse(null);
        BehaviorLog b = behaviorRepo.findFirstByRoomIdOrderByCreatedAtDesc(id).orElse(null);
        boolean online = s.getLastSeen() != null && s.getLastSeen().isAfter(LocalDateTime.now().minusSeconds(30));
        boolean occupied = s.getLastMotionAt() != null && s.getLastMotionAt().isAfter(LocalDateTime.now().minusMinutes(2));
        List<String> alerts = new ArrayList<>();
        if (r != null && r.getTemperature() > 35) alerts.add("Nhiệt độ quá cao (" + r.getTemperature() + "°C)");
        if (!online) alerts.add("Mất kết nối với ESP32");
        if (b != null && b.getCreatedAt().isAfter(LocalDateTime.now().minusMinutes(2))) {
            if (b.getUsingPhone() > 0) alerts.add("⚠️ Phát hiện " + b.getUsingPhone() + " học sinh đang sử dụng điện thoại!");
            if (b.getLeaningTable() + b.getBowingHead() >= 3) alerts.add("⚠️ Có nhiều học sinh ngủ gật hoặc mệt mỏi trong giờ học");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("room", rooms.findById(id).orElse(null));
        m.put("reading", r); m.put("state", s); m.put("online", online); m.put("occupied", occupied);
        m.put("currentClass", automation.currentClass(id).orElse(null)); m.put("alerts", alerts);
        m.put("latestBehavior", b);
        return m;
    }

    @GetMapping("/rooms/{id}/seats")
    public List<DeskSeat> seats(@PathVariable Long id) {
        return seatRepo.findByRoomIdOrderByRowNumAscColNumAsc(id);
    }

    // ===== CRUD QUAN LY HOC SINH TRONG SO DO CHO NGOI =====

    /** Cap nhat thong tin hoc sinh tai 1 cho ngoi */
    public record SeatStudentUpdate(String studentCode, String studentName) {}

    @PutMapping("/rooms/{roomId}/seats/{seatId}")
    public ResponseEntity<?> updateSeatStudent(
            @PathVariable Long roomId,
            @PathVariable Long seatId,
            @RequestBody SeatStudentUpdate body,
            Authentication auth) {
        DeskSeat seat = seatRepo.findById(seatId).orElse(null);
        if (seat == null || !seat.getRoomId().equals(roomId)) {
            return ResponseEntity.status(404).body(Map.of("message", "Khong tim thay cho ngoi"));
        }

        String oldName = seat.getStudentName();
        seat.setStudentCode(body.studentCode() != null ? body.studentCode().trim() : "");
        seat.setStudentName(body.studentName() != null ? body.studentName().trim() : "");

        // Neu xoa hoc sinh khoi cho ngoi (de trong)
        if (seat.getStudentName().isEmpty()) {
            seat.setCurrentStatus("absent");
            seat.setCurrentAction("");
        } else if (oldName == null || oldName.isEmpty()) {
            // Gan hoc sinh moi vao cho trong
            seat.setCurrentStatus("studying");
            seat.setCurrentAction("Hoc bai");
        }

        seatRepo.save(seat);

        // Ghi nhat ky
        String msg = seat.getStudentName().isEmpty()
            ? "Đã xóa học sinh khỏi chỗ " + seat.getSeatCode()
            : "Đã cập nhật chỗ " + seat.getSeatCode() + " → " + seat.getStudentName() + " (" + seat.getStudentCode() + ")";
        logs.save(new ActionLog(roomId, msg, auth.getName()));

        return ResponseEntity.ok(seat);
    }

    /** Reset toan bo danh sach hoc sinh cua 1 phong (xoa trang) */
    @PostMapping("/rooms/{roomId}/seats/reset")
    public ResponseEntity<?> resetAllSeats(
            @PathVariable Long roomId,
            Authentication auth) {
        List<DeskSeat> seats = seatRepo.findByRoomIdOrderByRowNumAscColNumAsc(roomId);
        for (DeskSeat seat : seats) {
            seat.setStudentCode("");
            seat.setStudentName("");
            seat.setCurrentStatus("absent");
            seat.setCurrentAction("");
            seat.setViolationCount(0);
            seat.setParticipationCount(0);
            seatRepo.save(seat);
        }
        logs.save(new ActionLog(roomId, "Đã xóa toàn bộ danh sách học sinh trong sơ đồ chỗ ngồi", auth.getName()));
        return ResponseEntity.ok(Map.of("status", "success", "message", "Da reset " + seats.size() + " cho ngoi"));
    }

    /** Lay cau hinh quy mo phong hoc */
    @GetMapping("/rooms/{id}/layout")
    public ResponseEntity<?> getRoomLayout(@PathVariable Long id) {
        Room r = rooms.findById(id).orElse(null);
        if (r == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
            "roomId", r.getId(),
            "code", r.getCode(),
            "name", r.getName(),
            "rowCount", r.getRowCount(),
            "colCount", r.getColCount(),
            "seatsPerDesk", r.getSeatsPerDesk(),
            "totalDesks", r.getRowCount() * r.getColCount(),
            "totalSeats", r.getRowCount() * r.getColCount() * r.getSeatsPerDesk()
        ));
    }

    /** Cap nhat cau hinh quy mo phong hoc va tu dong sinh lai so do cho ngoi */
    public record LayoutConfig(int rowCount, int colCount, int seatsPerDesk) {}

    @PostMapping("/rooms/{id}/layout")
    public ResponseEntity<?> updateRoomLayout(
            @PathVariable Long id,
            @RequestBody LayoutConfig cfg,
            Authentication auth) {
        Room r = rooms.findById(id).orElse(null);
        if (r == null) return ResponseEntity.notFound().build();

        int rows = Math.max(1, Math.min(10, cfg.rowCount()));
        int cols = Math.max(1, Math.min(6, cfg.colCount()));
        int perDesk = (cfg.seatsPerDesk() == 1) ? 1 : 2;

        r.setRowCount(rows);
        r.setColCount(cols);
        r.setSeatsPerDesk(perDesk);
        int totalCap = rows * cols * perDesk;
        r.setCapacity(totalCap);
        rooms.save(r);

        // Xoa cac cho ngoi cu cua phong nay
        List<DeskSeat> oldSeats = seatRepo.findByRoomIdOrderByRowNumAscColNumAsc(id);
        seatRepo.deleteAll(oldSeats);

        // Sinh danh sach cho ngoi moi voi toa do chuan hoa
        List<DeskSeat> newSeats = new ArrayList<>();
        int studentIdx = 1;
        double cellW = 1.0 / cols;
        double cellH = 1.0 / rows;

        String[] sampleNames = {
            "Nguyễn Văn An", "Trần Thị Mai", "Lê Hoàng Nam", "Phạm Thu Hà",
            "Vũ Minh Đức", "Đỗ Phương Linh", "Hoàng Tuấn Kiệt", "Bùi Thanh Thảo",
            "Ngô Gia Huy", "Đặng Thùy Dương", "Phan Quốc Bảo", "Trịnh Minh Châu",
            "Lý Hải Đăng", "Nguyễn Khánh Linh", "Trần Đình Phúc", "Võ Thị Ngọc",
            "Đinh Công Danh", "Huỳnh Thị Yến", "Mai Xuân Trường", "Lê Thị Bích Ngọc",
            "Phạm Quang Hưng", "Trần Thị Thanh Tâm", "Nguyễn Đức Anh", "Đoàn Thị Hồng",
            "Vương Gia Bảo", "Lâm Thị Thu", "Cao Minh Trí", "Đặng Ngọc Hân",
            "Tạ Quốc Việt", "Nguyễn Thị Lan", "Hồ Đức Mạnh", "Bùi Phương Anh",
            "Trần Văn Sơn", "Lê Thị Hương", "Phạm Minh Quân", "Nguyễn Thị Ánh",
            "Đỗ Xuân Hoàng", "Trịnh Thị Nhung", "Vũ Thành Long", "Hoàng Thị Mỹ Linh"
        };

        for (int row = 1; row <= rows; row++) {
            for (int col = 1; col <= cols; col++) {
                double baseX = (col - 1) * cellW;
                double baseY = (row - 1) * cellH;

                if (perDesk == 1) {
                    String code = "B" + row + "-D" + col;
                    String sCode = String.format("HS%02d", studentIdx);
                    String sName = (studentIdx <= sampleNames.length) ? sampleNames[studentIdx - 1] : ("Học sinh " + studentIdx);
                    DeskSeat seat = new DeskSeat(id, code, sCode, sName, row, col, "C",
                            baseX, baseY, baseX + cellW, baseY + cellH);
                    seat.setCurrentStatus("studying");
                    seat.setCurrentAction("Hoc bai");
                    newSeats.add(seat);
                    studentIdx++;
                } else {
                    String codeL = "B" + row + "-D" + col + "L";
                    String sCodeL = String.format("HS%02d", studentIdx);
                    String sNameL = (studentIdx <= sampleNames.length) ? sampleNames[studentIdx - 1] : ("Học sinh " + studentIdx);
                    DeskSeat seatL = new DeskSeat(id, codeL, sCodeL, sNameL, row, col, "L",
                            baseX, baseY, baseX + cellW / 2, baseY + cellH);
                    seatL.setCurrentStatus("studying");
                    seatL.setCurrentAction("Hoc bai");
                    newSeats.add(seatL);
                    studentIdx++;

                    String codeR = "B" + row + "-D" + col + "R";
                    String sCodeR = String.format("HS%02d", studentIdx);
                    String sNameR = (studentIdx <= sampleNames.length) ? sampleNames[studentIdx - 1] : ("Học sinh " + studentIdx);
                    DeskSeat seatR = new DeskSeat(id, codeR, sCodeR, sNameR, row, col, "R",
                            baseX + cellW / 2, baseY, baseX + cellW, baseY + cellH);
                    seatR.setCurrentStatus("studying");
                    seatR.setCurrentAction("Hoc bai");
                    newSeats.add(seatR);
                    studentIdx++;
                }
            }
        }
        seatRepo.saveAll(newSeats);

        logs.save(new ActionLog(id, "Đã cập nhật quy mô phòng: " + rows + " hàng x " + cols + " dãy (" + perDesk + " bạn/bàn = " + totalCap + " chỗ)", auth.getName()));

        return ResponseEntity.ok(Map.of(
            "status", "success",
            "rowCount", rows,
            "colCount", cols,
            "seatsPerDesk", perDesk,
            "totalSeats", newSeats.size(),
            "seats", newSeats
        ));
    }

    /** Import danh sach hoc sinh hang loat */
    public record BulkSeatEntry(String seatCode, String studentCode, String studentName) {}
    public record BulkSeatImport(List<BulkSeatEntry> students) {}

    @PostMapping("/rooms/{roomId}/seats/import")
    public ResponseEntity<?> importSeats(
            @PathVariable Long roomId,
            @RequestBody BulkSeatImport body,
            Authentication auth) {
        int updated = 0;
        for (BulkSeatEntry entry : body.students()) {
            seatRepo.findByRoomIdAndSeatCode(roomId, entry.seatCode()).ifPresent(seat -> {
                seat.setStudentCode(entry.studentCode() != null ? entry.studentCode().trim() : "");
                seat.setStudentName(entry.studentName() != null ? entry.studentName().trim() : "");
                if (!seat.getStudentName().isEmpty()) {
                    seat.setCurrentStatus("studying");
                    seat.setCurrentAction("Hoc bai");
                }
                seatRepo.save(seat);
            });
            updated++;
        }
        logs.save(new ActionLog(roomId, "Đã import danh sách " + updated + " học sinh vào sơ đồ chỗ ngồi", auth.getName()));
        return ResponseEntity.ok(Map.of("status", "success", "imported", updated));
    }

    @GetMapping("/rooms/{id}/history")
    public List<SensorReading> history(@PathVariable Long id, @RequestParam(defaultValue = "1") int hours) {
        List<SensorReading> all = readings.findByRoomIdAndCreatedAtAfterOrderByCreatedAtAsc(id, LocalDateTime.now().minusHours(hours));
        int step = Math.max(1, all.size() / 120);
        List<SensorReading> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += step) out.add(all.get(i));
        return out;
    }

    @GetMapping("/rooms/{id}/behavior/latest")
    public ResponseEntity<?> latestBehavior(@PathVariable Long id) {
        return ResponseEntity.ok(behaviorRepo.findFirstByRoomIdOrderByCreatedAtDesc(id)
                .orElse(new BehaviorLog(id, 0, 0, 0, 0, 0, 0, 0)));
    }

    @GetMapping("/rooms/{id}/behavior/history")
    public List<BehaviorLog> behaviorHistory(@PathVariable Long id, @RequestParam(defaultValue = "1") int hours) {
        return behaviorRepo.findByRoomIdAndCreatedAtAfterOrderByCreatedAtAsc(id, LocalDateTime.now().minusHours(hours));
    }

    public record Control(Boolean light, Boolean fan, Boolean ac, Integer acTemp, Boolean auto) {}

    @PostMapping("/rooms/{id}/control")
    public ResponseEntity<DeviceState> control(@PathVariable Long id, @RequestBody Control c, Authentication a) {
        DeviceState s = states.findById(id).orElse(new DeviceState(id));
        StringBuilder msg = new StringBuilder();
        if (c.auto() != null && c.auto() != s.isAuto()) { s.setAuto(c.auto()); msg.append("Chế độ tự động ").append(c.auto() ? "BẬT; " : "TẮT; "); }
        if (c.light() != null && c.light() != s.isLight()) { s.setLight(c.light()); msg.append("Đèn ").append(c.light() ? "BẬT; " : "TẮT; "); }
        if (c.fan() != null && c.fan() != s.isFan()) { s.setFan(c.fan()); msg.append("Quạt ").append(c.fan() ? "BẬT; " : "TẮT; "); }
        if (c.ac() != null && c.ac() != s.isAc()) { s.setAc(c.ac()); msg.append("Điều hòa ").append(c.ac() ? "BẬT; " : "TẮT; "); }
        if (c.acTemp() != null && c.acTemp() >= 16 && c.acTemp() <= 30 && c.acTemp() != s.getAcTemp()) { s.setAcTemp(c.acTemp()); msg.append("Đặt điều hòa ").append(c.acTemp()).append("°C; "); }
        states.save(s);
        if (msg.length() > 0) logs.save(new ActionLog(id, msg.toString().trim(), a.getName()));
        return ResponseEntity.ok(s);
    }

    @GetMapping("/rooms/{id}/logs")
    public List<ActionLog> logs(@PathVariable Long id) { return logs.findTop50ByRoomIdOrderByCreatedAtDesc(id); }
}
