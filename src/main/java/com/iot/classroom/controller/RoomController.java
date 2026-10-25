package com.iot.classroom.controller;

import com.iot.classroom.model.*;
import com.iot.classroom.repository.Repos;
import com.iot.classroom.service.AutomationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class RoomController {
    private final Repos.RoomRepo rooms;
    private final Repos.ReadingRepo readings;
    private final Repos.StateRepo states;
    private final Repos.LogRepo logs;
    private final Repos.UserRepo users;
    private final Repos.BehaviorRepo behaviorRepo;
    private final Repos.SeatRepo seatRepo;
    private final Repos.StudentRepo studentRepo;
    private final AutomationService automation;

    public RoomController(Repos.RoomRepo rooms, Repos.ReadingRepo readings,
                          Repos.StateRepo states, Repos.LogRepo logs,
                          Repos.UserRepo users, Repos.BehaviorRepo behaviorRepo,
                          Repos.SeatRepo seatRepo, Repos.StudentRepo studentRepo, AutomationService automation) {
        this.rooms = rooms; this.readings = readings; this.states = states;
        this.logs = logs; this.users = users; this.behaviorRepo = behaviorRepo;
        this.seatRepo = seatRepo; this.studentRepo = studentRepo; this.automation = automation;
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication a) {
        if (a == null) return Map.of("username", "guest", "fullName", "Khách vãng lai", "role", "TEACHER");
        AppUser u = users.findByUsername(a.getName()).orElse(null);
        if (u == null) {
            return Map.of("username", a.getName(), "fullName", a.getName(), "role", "TEACHER");
        }
        return Map.of("username", u.getUsername(), "fullName", u.getFullName(), "role", u.getRole());
    }

    @GetMapping("/rooms")
    public List<Room> rooms() { return rooms.findAll(); }

    public record RoomCreateReq(String code, String name, int rowCount, int colCount, int seatsPerDesk) {}

    @PostMapping("/rooms")
    public ResponseEntity<?> createRoom(@RequestBody RoomCreateReq req, Authentication auth) {
        if (req.code() == null || req.code().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Mã phòng học không được để trống!"));
        }
        String code = req.code().trim().toUpperCase();
        if (rooms.findByCode(code).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Phòng học với mã '" + code + "' đã tồn tại!"));
        }
        String name = (req.name() != null && !req.name().trim().isEmpty()) ? req.name().trim() : ("Phòng học " + code);
        int rows = (req.rowCount() > 0 && req.rowCount() <= 10) ? req.rowCount() : 5;
        int cols = (req.colCount() > 0 && req.colCount() <= 8) ? req.colCount() : 4;
        int perDesk = (req.seatsPerDesk() == 1 || req.seatsPerDesk() == 2) ? req.seatsPerDesk() : 2;
        int capacity = rows * cols * perDesk;

        Room room = new Room(null, code, name, capacity);
        room = rooms.save(room);

        // Khoi tao trang thai thiet bi
        states.save(new DeviceState(room.getId()));

        // Sinh danh sach ghe ngoi
        List<DeskSeat> newSeats = new ArrayList<>();
        int studentIdx = 1;
        double cellW = 1.0 / cols;
        double cellH = 1.0 / rows;

        String[] sampleNames = {
            "Nguyễn Văn An", "Trần Thị Mai", "Lê Hoàng Nam", "Phạm Thu Hà",
            "Vũ Minh Đức", "Đỗ Phương Linh", "Hoàng Tuấn Kiệt", "Bùi Thanh Thảo",
            "Ngô Gia Huy", "Đặng Thùy Dung", "Phan Quốc Bảo", "Trịnh Minh Châu",
            "Lê Hải Đăng", "Nguyễn Khánh Linh", "Trần Đình Phúc", "Võ Thị Ngọc",
            "Đinh Công Danh", "Huỳnh Thị Yến", "Mai Xuân Trọng", "Lê Thị Bích Ngọc",
            "Phạm Quang Hưng", "Trần Thị Thanh Tâm", "Nguyễn Đức Anh", "Đoàn Thị Hường",
            "Vương Gia Bảo", "Lâm Thị Thu", "Cao Minh Trí", "Đặng Ngọc Hân",
            "Tạ Quốc Việt", "Nguyễn Thị Lan", "Hồ Đức Mạnh", "Bùi Phương Anh",
            "Trần Văn Sơn", "Lê Thị Hằng", "Phạm Minh Quân", "Nguyễn Thị Ánh",
            "Đỗ Xuân Hoàng", "Trịnh Thị Nhung", "Vũ Thành Long", "Hoàng Thị Mỹ Linh"
        };

        for (int row = 1; row <= rows; row++) {
            for (int col = 1; col <= cols; col++) {
                double baseX = (col - 1) * cellW;
                double baseY = (row - 1) * cellH;

                if (perDesk == 1) {
                    String seatCode = "B" + row + "-D" + col;
                    String sCode = String.format("HS%02d", studentIdx);
                    String sName = (studentIdx <= sampleNames.length) ? sampleNames[studentIdx - 1] : ("Học sinh " + studentIdx);
                    DeskSeat seat = new DeskSeat(room.getId(), seatCode, sCode, sName, row, col, "C",
                            baseX, baseY, baseX + cellW, baseY + cellH);
                    seat.setCurrentStatus("studying");
                    seat.setCurrentAction("Hoc bai");
                    newSeats.add(seat);
                    studentIdx++;
                } else {
                    String codeL = "B" + row + "-D" + col + "L";
                    String sCodeL = String.format("HS%02d", studentIdx);
                    String sNameL = (studentIdx <= sampleNames.length) ? sampleNames[studentIdx - 1] : ("Học sinh " + studentIdx);
                    DeskSeat seatL = new DeskSeat(room.getId(), codeL, sCodeL, sNameL, row, col, "L",
                            baseX, baseY, baseX + cellW / 2, baseY + cellH);
                    seatL.setCurrentStatus("studying");
                    seatL.setCurrentAction("Hoc bai");
                    newSeats.add(seatL);
                    studentIdx++;

                    String codeR = "B" + row + "-D" + col + "R";
                    String sCodeR = String.format("HS%02d", studentIdx);
                    String sNameR = (studentIdx <= sampleNames.length) ? sampleNames[studentIdx - 1] : ("Học sinh " + studentIdx);
                    DeskSeat seatR = new DeskSeat(room.getId(), codeR, sCodeR, sNameR, row, col, "R",
                            baseX + cellW / 2, baseY, baseX + cellW, baseY + cellH);
                    seatR.setCurrentStatus("studying");
                    seatR.setCurrentAction("Hoc bai");
                    newSeats.add(seatR);
                    studentIdx++;
                }
            }
        }
        seatRepo.saveAll(newSeats);

        String creator = auth != null ? auth.getName() : "ADMIN";
        logs.save(new ActionLog(room.getId(), "Đã tạo phòng học mới: " + code + " - " + name + " (" + capacity + " chỗ)", creator));

        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Đã tạo phòng học thành công!",
            "room", room
        ));
    }

    @DeleteMapping("/rooms/{id}")
    public ResponseEntity<?> deleteRoom(@PathVariable Long id, Authentication auth) {
        Room r = rooms.findById(id).orElse(null);
        if (r == null) return ResponseEntity.notFound().build();

        long roomCount = rooms.count();
        if (roomCount <= 1) {
            return ResponseEntity.badRequest().body(Map.of("message", "Không thể xóa phòng học duy nhất trong hệ thống!"));
        }

        // Xoa cac du lieu lien quan
        seatRepo.deleteByRoomId(id);
        states.deleteById(id);
        rooms.delete(r);

        String actor = auth != null ? auth.getName() : "ADMIN";
        logs.save(new ActionLog(id, "Đã xóa phòng học: " + r.getCode() + " - " + r.getName(), actor));

        return ResponseEntity.ok(Map.of("status", "success", "message", "Đã xóa phòng học " + r.getCode()));
    }

    @GetMapping("/rooms/{id}/status")
    public Map<String, Object> status(@PathVariable Long id) {
        DeviceState s = states.findById(id).orElse(new DeviceState(id));
        SensorReading r = readings.findFirstByRoomIdOrderByCreatedAtDesc(id).orElse(null);
        Map<String, Object> out = new HashMap<>();
        out.put("state", s);
        out.put("device", s);
        out.put("sensor", r);
        out.put("alerts", automation.getAlerts(id));
        return out;
    }

    @GetMapping("/rooms/{id}/seats")
    public List<DeskSeat> getSeats(@PathVariable Long id) {
        return seatRepo.findByRoomIdOrderByRowNumAscColNumAsc(id);
    }

    public record SeatUpdateReq(String studentName, String studentCode, String currentStatus, String currentAction, String notes) {}

    @PutMapping("/rooms/{roomId}/seats/{seatCode}")
    public ResponseEntity<?> updateSeat(
            @PathVariable Long roomId,
            @PathVariable String seatCode,
            @RequestBody SeatUpdateReq req,
            Authentication auth) {
        DeskSeat seat = seatRepo.findByRoomIdAndSeatCode(roomId, seatCode).orElse(null);
        if (seat == null) return ResponseEntity.notFound().build();

        if (req.studentName() != null) seat.setStudentName(req.studentName().trim());
        if (req.studentCode() != null) seat.setStudentCode(req.studentCode().trim());
        if (req.currentStatus() != null) seat.setCurrentStatus(req.currentStatus().trim());
        if (req.currentAction() != null) seat.setCurrentAction(req.currentAction().trim());
        if (req.notes() != null) seat.setNotes(req.notes().trim());

        seatRepo.save(seat);

        // Dong bo vao ho so hoc sinh
        if (seat.getStudentCode() != null && !seat.getStudentCode().isBlank()) {
            studentRepo.findByStudentCode(seat.getStudentCode()).ifPresent(st -> {
                if (req.studentName() != null) st.setFullName(req.studentName().trim());
                if (req.notes() != null) st.setNotes(req.notes().trim());
                st.setRoomId(roomId);
                st.setSeatCode(seatCode);
                studentRepo.save(st);
            });
        }

        String actor = auth != null ? auth.getName() : "USER";
        logs.save(new ActionLog(roomId, "Cập nhật thông tin ghế " + seatCode + ": " + seat.getStudentName(), actor));

        return ResponseEntity.ok(Map.of("status", "success", "seat", seat));
    }

    @GetMapping("/rooms/{id}/layout")
    public Map<String, Object> getLayout(@PathVariable Long id) {
        List<DeskSeat> list = seatRepo.findByRoomIdOrderByRowNumAscColNumAsc(id);
        int maxRow = 0, maxCol = 0;
        boolean hasR = false;
        for (DeskSeat s : list) {
            if (s.getRowNum() > maxRow) maxRow = s.getRowNum();
            if (s.getColNum() > maxCol) maxCol = s.getColNum();
            if ("R".equalsIgnoreCase(s.getSide())) hasR = true;
        }
        int seatsPerDesk = hasR ? 2 : 1;
        Map<String, Object> res = new HashMap<>();
        res.put("roomId", id);
        res.put("rowCount", Math.max(maxRow, 1));
        res.put("colCount", Math.max(maxCol, 1));
        res.put("seatsPerDesk", seatsPerDesk);
        res.put("totalSeats", list.size());
        return res;
    }

    public record LayoutConfigReq(int rowCount, int colCount, int seatsPerDesk) {}

    @PostMapping("/rooms/{id}/layout")
    public ResponseEntity<?> updateLayout(
            @PathVariable Long id,
            @RequestBody LayoutConfigReq req,
            Authentication auth) {
        Room r = rooms.findById(id).orElse(null);
        if (r == null) return ResponseEntity.notFound().build();

        int rows = Math.min(Math.max(req.rowCount(), 1), 10);
        int cols = Math.min(Math.max(req.colCount(), 1), 8);
        int perDesk = (req.seatsPerDesk() == 1 || req.seatsPerDesk() == 2) ? req.seatsPerDesk() : 2;

        int totalCap = rows * cols * perDesk;
        r.setCapacity(totalCap);
        rooms.save(r);

        List<DeskSeat> oldSeats = seatRepo.findByRoomIdOrderByRowNumAscColNumAsc(id);
        seatRepo.deleteAll(oldSeats);

        // Lay danh sach hoc sinh cua phong nay va danh sach cho xep cho
        List<Student> roomStudents = new ArrayList<>(studentRepo.findByRoomIdOrderByStudentCodeAsc(id));
        List<Student> unassignedStudents = new ArrayList<>(studentRepo.findByRoomIdIsNull());

        // Neu giam cho (vi du 40 xuong 20), cac hoc sinh vuot qua suc chua se chuyen sang trang thai cho xep (khong bi xoa)
        for (int i = totalCap; i < roomStudents.size(); i++) {
            Student extra = roomStudents.get(i);
            extra.setSeatCode(null);
            extra.setStatus("unassigned");
            studentRepo.save(extra);
        }

        List<DeskSeat> newSeats = new ArrayList<>();
        int studentIdx = 1;
        double cellW = 1.0 / cols;
        double cellH = 1.0 / rows;

        String[] sampleNames = {
            "Nguyễn Văn An", "Trần Thị Mai", "Lê Hoàng Nam", "Phạm Thu Hà",
            "Vũ Minh Đức", "Đỗ Phương Linh", "Hoàng Tuấn Kiệt", "Bùi Thanh Thảo",
            "Ngô Gia Huy", "Đặng Thùy Dung", "Phan Quốc Bảo", "Trịnh Minh Châu",
            "Lê Hải Đăng", "Nguyễn Khánh Linh", "Trần Đình Phúc", "Võ Thị Ngọc",
            "Đinh Công Danh", "Huỳnh Thị Yến", "Mai Xuân Trọng", "Lê Thị Bích Ngọc",
            "Phạm Quang Hưng", "Trần Thị Thanh Tâm", "Nguyễn Đức Anh", "Đoàn Thị Hường",
            "Vương Gia Bảo", "Lâm Thị Thu", "Cao Minh Trí", "Đặng Ngọc Hân",
            "Tạ Quốc Việt", "Nguyễn Thị Lan", "Hồ Đức Mạnh", "Bùi Phương Anh",
            "Trần Văn Sơn", "Lê Thị Hằng", "Phạm Minh Quân", "Nguyễn Thị Ánh",
            "Đỗ Xuân Hoàng", "Trịnh Thị Nhung", "Vũ Thành Long", "Hoàng Thị Mỹ Linh"
        };

        for (int row = 1; row <= rows; row++) {
            for (int col = 1; col <= cols; col++) {
                double baseX = (col - 1) * cellW;
                double baseY = (row - 1) * cellH;

                if (perDesk == 1) {
                    String code = "B" + row + "-D" + col;
                    String sCode;
                    String sName;
                    if (studentIdx <= roomStudents.size() && studentIdx <= totalCap) {
                        Student st = roomStudents.get(studentIdx - 1);
                        sCode = st.getStudentCode();
                        sName = st.getFullName();
                        st.setSeatCode(code);
                        st.setStatus("studying");
                        studentRepo.save(st);
                    } else if (!unassignedStudents.isEmpty()) {
                        Student st = unassignedStudents.remove(0);
                        sCode = st.getStudentCode();
                        sName = st.getFullName();
                        st.setRoomId(id);
                        st.setSeatCode(code);
                        st.setStatus("studying");
                        studentRepo.save(st);
                    } else {
                        sCode = String.format("HS%02d", studentIdx);
                        sName = (studentIdx <= sampleNames.length) ? sampleNames[studentIdx - 1] : ("Học sinh " + studentIdx);
                        Student newSt = new Student(sCode, sName, "12A1", "Nam", "2008-01-01", "", id, code);
                        studentRepo.save(newSt);
                    }

                    DeskSeat seat = new DeskSeat(id, code, sCode, sName, row, col, "C",
                            baseX, baseY, baseX + cellW, baseY + cellH);
                    seat.setCurrentStatus("studying");
                    seat.setCurrentAction("Hoc bai");
                    newSeats.add(seat);
                    studentIdx++;
                } else {
                    String codeL = "B" + row + "-D" + col + "L";
                    String sCodeL;
                    String sNameL;
                    if (studentIdx <= roomStudents.size() && studentIdx <= totalCap) {
                        Student st = roomStudents.get(studentIdx - 1);
                        sCodeL = st.getStudentCode();
                        sNameL = st.getFullName();
                        st.setSeatCode(codeL);
                        st.setStatus("studying");
                        studentRepo.save(st);
                    } else if (!unassignedStudents.isEmpty()) {
                        Student st = unassignedStudents.remove(0);
                        sCodeL = st.getStudentCode();
                        sNameL = st.getFullName();
                        st.setRoomId(id);
                        st.setSeatCode(codeL);
                        st.setStatus("studying");
                        studentRepo.save(st);
                    } else {
                        sCodeL = String.format("HS%02d", studentIdx);
                        sNameL = (studentIdx <= sampleNames.length) ? sampleNames[studentIdx - 1] : ("Học sinh " + studentIdx);
                        Student newSt = new Student(sCodeL, sNameL, "12A1", "Nam", "2008-01-01", "", id, codeL);
                        studentRepo.save(newSt);
                    }

                    DeskSeat seatL = new DeskSeat(id, codeL, sCodeL, sNameL, row, col, "L",
                            baseX, baseY, baseX + cellW / 2, baseY + cellH);
                    seatL.setCurrentStatus("studying");
                    seatL.setCurrentAction("Hoc bai");
                    newSeats.add(seatL);
                    studentIdx++;

                    String codeR = "B" + row + "-D" + col + "R";
                    String sCodeR;
                    String sNameR;
                    if (studentIdx <= roomStudents.size() && studentIdx <= totalCap) {
                        Student st = roomStudents.get(studentIdx - 1);
                        sCodeR = st.getStudentCode();
                        sNameR = st.getFullName();
                        st.setSeatCode(codeR);
                        st.setStatus("studying");
                        studentRepo.save(st);
                    } else if (!unassignedStudents.isEmpty()) {
                        Student st = unassignedStudents.remove(0);
                        sCodeR = st.getStudentCode();
                        sNameR = st.getFullName();
                        st.setRoomId(id);
                        st.setSeatCode(codeR);
                        st.setStatus("studying");
                        studentRepo.save(st);
                    } else {
                        sCodeR = String.format("HS%02d", studentIdx);
                        sNameR = (studentIdx <= sampleNames.length) ? sampleNames[studentIdx - 1] : ("Học sinh " + studentIdx);
                        Student newSt = new Student(sCodeR, sNameR, "12A1", "Nam", "2008-01-01", "", id, codeR);
                        studentRepo.save(newSt);
                    }

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

        String actor = auth != null ? auth.getName() : "ADMIN";
        logs.save(new ActionLog(id, "Đã cập nhật quy mô phòng: " + rows + " hàng x " + cols + " dãy (" + perDesk + " ghế/bàn = " + totalCap + " chỗ)", actor));

        return ResponseEntity.ok(Map.of(
            "status", "success",
            "rowCount", rows,
            "colCount", cols,
            "seatsPerDesk", perDesk,
            "totalSeats", newSeats.size(),
            "seats", newSeats
        ));
    }

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
        String actor = auth != null ? auth.getName() : "ADMIN";
        logs.save(new ActionLog(roomId, "Đã import danh sách " + updated + " học sinh vào sơ đồ chỗ ngồi", actor));
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

    public record Control(
        Boolean light,
        Boolean fan,
        Boolean ac,
        Integer acTemp,
        Boolean auto,
        String device,
        Boolean state
    ) {}

    @PostMapping("/rooms/{id}/control")
    public ResponseEntity<DeviceState> control(@PathVariable Long id, @RequestBody Control c, Authentication a) {
        DeviceState s = states.findById(id).orElse(new DeviceState(id));
        StringBuilder msg = new StringBuilder();

        Boolean autoVal = c.auto() != null ? c.auto() : ("auto".equalsIgnoreCase(c.device()) ? c.state() : null);
        Boolean lightVal = c.light() != null ? c.light() : ("light".equalsIgnoreCase(c.device()) ? c.state() : null);
        Boolean fanVal = c.fan() != null ? c.fan() : ("fan".equalsIgnoreCase(c.device()) ? c.state() : null);
        Boolean acVal = c.ac() != null ? c.ac() : ("ac".equalsIgnoreCase(c.device()) ? c.state() : null);

        if (autoVal != null && autoVal != s.isAuto()) {
            s.setAuto(autoVal);
            msg.append("Chế độ tự động ").append(autoVal ? "BẬT; " : "TẮT; ");
        }
        if (lightVal != null && lightVal != s.isLight()) {
            s.setLight(lightVal);
            msg.append("Đèn ").append(lightVal ? "BẬT; " : "TẮT; ");
        }
        if (fanVal != null && fanVal != s.isFan()) {
            s.setFan(fanVal);
            msg.append("Quạt ").append(fanVal ? "BẬT; " : "TẮT; ");
        }
        if (acVal != null && acVal != s.isAc()) {
            s.setAc(acVal);
            msg.append("Điều hòa ").append(acVal ? "BẬT; " : "TẮT; ");
        }
        if (c.acTemp() != null && c.acTemp() >= 16 && c.acTemp() <= 30 && c.acTemp() != s.getAcTemp()) {
            s.setAcTemp(c.acTemp());
            msg.append("Đặt điều hòa ").append(c.acTemp()).append("°C; ");
        }

        states.save(s);
        String actor = a != null ? a.getName() : "USER";
        if (msg.length() > 0) logs.save(new ActionLog(id, msg.toString().trim(), actor));
        if (s.isAuto()) {
            automation.apply(id);
            s = states.findById(id).orElse(s);
        }
        return ResponseEntity.ok(s);
    }

    @GetMapping("/rooms/{id}/logs")
    public List<ActionLog> logs(@PathVariable Long id) { return logs.findTop50ByRoomIdOrderByCreatedAtDesc(id); }
}
