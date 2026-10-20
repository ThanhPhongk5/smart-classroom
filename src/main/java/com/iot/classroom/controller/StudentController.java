package com.iot.classroom.controller;

import com.iot.classroom.model.DeskSeat;
import com.iot.classroom.model.Student;
import com.iot.classroom.repository.Repos;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/students")
public class StudentController {

    private final Repos.StudentRepo studentRepo;
    private final Repos.SeatRepo seatRepo;
    private final Repos.RoomRepo roomRepo;

    public StudentController(Repos.StudentRepo studentRepo, Repos.SeatRepo seatRepo, Repos.RoomRepo roomRepo) {
        this.studentRepo = studentRepo;
        this.seatRepo = seatRepo;
        this.roomRepo = roomRepo;
    }

    @GetMapping
    public List<Student> list(
            @RequestParam(required = false) Long roomId,
            @RequestParam(required = false) String className,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status) {
        List<Student> list = studentRepo.findAllByOrderByStudentCodeAsc();

        if (roomId != null) {
            if (roomId == -1) {
                // Chua xep phong
                list = list.stream().filter(s -> s.getRoomId() == null).collect(Collectors.toList());
            } else {
                list = list.stream().filter(s -> Objects.equals(s.getRoomId(), roomId)).collect(Collectors.toList());
            }
        }
        if (className != null && !className.isBlank() && !className.equalsIgnoreCase("all")) {
            list = list.stream().filter(s -> className.equalsIgnoreCase(s.getClassName())).collect(Collectors.toList());
        }
        if (status != null && !status.isBlank() && !status.equalsIgnoreCase("all")) {
            list = list.stream().filter(s -> status.equalsIgnoreCase(s.getStatus())).collect(Collectors.toList());
        }
        if (search != null && !search.isBlank()) {
            String q = search.trim().toLowerCase();
            list = list.stream().filter(s -> 
                (s.getFullName() != null && s.getFullName().toLowerCase().contains(q)) ||
                (s.getStudentCode() != null && s.getStudentCode().toLowerCase().contains(q)) ||
                (s.getClassName() != null && s.getClassName().toLowerCase().contains(q))
            ).collect(Collectors.toList());
        }
        return list;
    }

    @GetMapping("/classes")
    public List<String> getClasses() {
        return studentRepo.findAll().stream()
                .map(Student::getClassName)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    @GetMapping("/summary")
    public Map<String, Object> getSummary() {
        List<Student> all = studentRepo.findAll();
        long total = all.size();
        long assigned = all.stream().filter(s -> s.getSeatCode() != null && !s.getSeatCode().isBlank()).count();
        long unassigned = total - assigned;
        long totalViolations = all.stream().mapToInt(Student::getViolationCount).sum();
        long totalParticipations = all.stream().mapToInt(Student::getParticipationCount).sum();

        Map<String, Object> map = new HashMap<>();
        map.put("total", total);
        map.put("assigned", assigned);
        map.put("unassigned", unassigned);
        map.put("totalViolations", totalViolations);
        map.put("totalParticipations", totalParticipations);
        return map;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Student> getOne(@PathVariable Long id) {
        return studentRepo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    public record StudentReq(
            String studentCode,
            String fullName,
            String className,
            String gender,
            String dateOfBirth,
            String phone,
            String email,
            Long roomId,
            String seatCode,
            String notes
    ) {}

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody StudentReq req, Authentication auth) {
        if (req.studentCode() == null || req.studentCode().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Mã học sinh không được để trống"));
        }
        if (req.fullName() == null || req.fullName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Họ và tên học sinh không được để trống"));
        }
        if (studentRepo.findByStudentCode(req.studentCode().trim()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Mã học sinh " + req.studentCode() + " đã tồn tại trong hệ thống"));
        }

        Student s = new Student();
        s.setStudentCode(req.studentCode().trim().toUpperCase());
        s.setFullName(req.fullName().trim());
        s.setClassName(req.className() != null && !req.className().isBlank() ? req.className().trim() : "12A1");
        s.setGender(req.gender() != null ? req.gender() : "Nam");
        s.setDateOfBirth(req.dateOfBirth() != null ? req.dateOfBirth() : "2008-01-01");
        s.setPhone(req.phone() != null ? req.phone().trim() : "");
        s.setEmail(req.email() != null ? req.email().trim() : "");
        s.setNotes(req.notes() != null ? req.notes().trim() : "");
        s.setRoomId(req.roomId());
        s.setSeatCode(req.seatCode() != null && !req.seatCode().isBlank() ? req.seatCode().trim() : null);
        s.setStatus((s.getSeatCode() != null) ? "studying" : "unassigned");

        // Neu co xep ghe
        if (s.getRoomId() != null && s.getSeatCode() != null) {
            seatRepo.findByRoomIdAndSeatCode(s.getRoomId(), s.getSeatCode()).ifPresent(seat -> {
                seat.setStudentCode(s.getStudentCode());
                seat.setStudentName(s.getFullName());
                seat.setCurrentStatus("studying");
                seat.setCurrentAction("Hoc bai");
                seatRepo.save(seat);
            });
        }

        Student saved = studentRepo.save(s);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody StudentReq req) {
        Student s = studentRepo.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();

        if (req.studentCode() != null && !req.studentCode().isBlank()) {
            String newCode = req.studentCode().trim().toUpperCase();
            if (!newCode.equalsIgnoreCase(s.getStudentCode()) && studentRepo.findByStudentCode(newCode).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Mã học sinh " + newCode + " đã được sử dụng"));
            }
            s.setStudentCode(newCode);
        }
        if (req.fullName() != null && !req.fullName().isBlank()) {
            s.setFullName(req.fullName().trim());
        }
        if (req.className() != null) s.setClassName(req.className().trim());
        if (req.gender() != null) s.setGender(req.gender());
        if (req.dateOfBirth() != null) s.setDateOfBirth(req.dateOfBirth());
        if (req.phone() != null) s.setPhone(req.phone().trim());
        if (req.email() != null) s.setEmail(req.email().trim());
        if (req.notes() != null) s.setNotes(req.notes().trim());

        // Dong bo voi ghe ngoi neu co thay doi ten
        if (s.getRoomId() != null && s.getSeatCode() != null) {
            seatRepo.findByRoomIdAndSeatCode(s.getRoomId(), s.getSeatCode()).ifPresent(seat -> {
                seat.setStudentCode(s.getStudentCode());
                seat.setStudentName(s.getFullName());
                seatRepo.save(seat);
            });
        }

        Student saved = studentRepo.save(s);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        Student s = studentRepo.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();

        // Giai phong ghe ngoi neu dang giu cho
        if (s.getRoomId() != null && s.getSeatCode() != null) {
            seatRepo.findByRoomIdAndSeatCode(s.getRoomId(), s.getSeatCode()).ifPresent(seat -> {
                seat.setStudentCode("");
                seat.setStudentName("");
                seat.setCurrentStatus("absent");
                seat.setCurrentAction("");
                seatRepo.save(seat);
            });
        }

        studentRepo.delete(s);
        return ResponseEntity.ok(Map.of("message", "Đã xóa học sinh " + s.getFullName() + " thành công"));
    }

    public record AssignReq(Long roomId, String seatCode) {}

    @PostMapping("/{id}/assign-seat")
    @Transactional
    public ResponseEntity<?> assignSeat(@PathVariable Long id, @RequestBody AssignReq req) {
        Student s = studentRepo.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();

        // Giai phong cho cu
        if (s.getRoomId() != null && s.getSeatCode() != null) {
            seatRepo.findByRoomIdAndSeatCode(s.getRoomId(), s.getSeatCode()).ifPresent(oldSeat -> {
                oldSeat.setStudentCode("");
                oldSeat.setStudentName("");
                oldSeat.setCurrentStatus("absent");
                oldSeat.setCurrentAction("");
                seatRepo.save(oldSeat);
            });
        }

        if (req.roomId() == null || req.seatCode() == null || req.seatCode().isBlank()) {
            s.setRoomId(null);
            s.setSeatCode(null);
            s.setStatus("unassigned");
            studentRepo.save(s);
            return ResponseEntity.ok(Map.of("message", "Đã hủy xếp chỗ cho học sinh", "student", s));
        }

        DeskSeat targetSeat = seatRepo.findByRoomIdAndSeatCode(req.roomId(), req.seatCode().trim()).orElse(null);
        if (targetSeat == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Vị trí ghế " + req.seatCode() + " không tồn tại trong phòng này"));
        }

        // Gan vao ghe moi
        targetSeat.setStudentCode(s.getStudentCode());
        targetSeat.setStudentName(s.getFullName());
        targetSeat.setCurrentStatus("studying");
        targetSeat.setCurrentAction("Hoc bai");
        targetSeat.setLastSeen(LocalDateTime.now());
        seatRepo.save(targetSeat);

        s.setRoomId(req.roomId());
        s.setSeatCode(targetSeat.getSeatCode());
        s.setStatus("studying");
        studentRepo.save(s);

        return ResponseEntity.ok(Map.of("message", "Đã xếp học sinh vào ghế " + targetSeat.getSeatCode(), "student", s));
    }

    @PostMapping("/{id}/unassign")
    @Transactional
    public ResponseEntity<?> unassign(@PathVariable Long id) {
        return assignSeat(id, new AssignReq(null, null));
    }

    @PostMapping("/auto-assign/{roomId}")
    @Transactional
    public ResponseEntity<?> autoAssign(@PathVariable Long roomId, @RequestParam(required = false) String className) {
        List<DeskSeat> seats = seatRepo.findByRoomIdOrderByRowNumAscColNumAsc(roomId);
        if (seats.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Phòng học chưa có sơ đồ chỗ ngồi"));
        }

        // Tim cac ghe con trong
        List<DeskSeat> emptySeats = seats.stream()
                .filter(st -> st.getStudentCode() == null || st.getStudentCode().isBlank() || "absent".equalsIgnoreCase(st.getCurrentStatus()))
                .collect(Collectors.toList());

        // Tim hoc sinh chua duoc xep cho
        List<Student> unassignedList = studentRepo.findAllByOrderByStudentCodeAsc().stream()
                .filter(s -> s.getSeatCode() == null || s.getSeatCode().isBlank() || "unassigned".equalsIgnoreCase(s.getStatus()))
                .collect(Collectors.toList());

        if (className != null && !className.isBlank() && !className.equalsIgnoreCase("all")) {
            unassignedList = unassignedList.stream()
                    .filter(s -> className.equalsIgnoreCase(s.getClassName()))
                    .collect(Collectors.toList());
        }

        int count = 0;
        int max = Math.min(emptySeats.size(), unassignedList.size());
        for (int i = 0; i < max; i++) {
            DeskSeat seat = emptySeats.get(i);
            Student student = unassignedList.get(i);

            seat.setStudentCode(student.getStudentCode());
            seat.setStudentName(student.getFullName());
            seat.setCurrentStatus("studying");
            seat.setCurrentAction("Hoc bai");
            seat.setLastSeen(LocalDateTime.now());
            seatRepo.save(seat);

            student.setRoomId(roomId);
            student.setSeatCode(seat.getSeatCode());
            student.setStatus("studying");
            studentRepo.save(student);
            count++;
        }

        return ResponseEntity.ok(Map.of(
                "message", "Đã tự động xếp chỗ thành công cho " + count + " học sinh vào phòng",
                "assignedCount", count,
                "remainingSeats", emptySeats.size() - count,
                "remainingUnassigned", unassignedList.size() - count
        ));
    }
}
