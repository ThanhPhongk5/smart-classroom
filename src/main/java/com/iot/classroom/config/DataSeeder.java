package com.iot.classroom.config;

import com.iot.classroom.model.*;
import com.iot.classroom.repository.Repos;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import java.time.LocalTime;

@Component
public class DataSeeder implements CommandLineRunner {
    private final Repos.UserRepo users;
    private final Repos.RoomRepo rooms;
    private final Repos.StateRepo states;
    private final Repos.ScheduleRepo schedules;
    private final Repos.BehaviorRepo behaviorRepo;
    private final Repos.SeatRepo seatRepo;
    private final PasswordEncoder enc;

    public DataSeeder(Repos.UserRepo users, Repos.RoomRepo rooms, Repos.StateRepo states,
                      Repos.ScheduleRepo schedules, Repos.BehaviorRepo behaviorRepo,
                      Repos.SeatRepo seatRepo, PasswordEncoder enc) {
        this.users = users; this.rooms = rooms; this.states = states;
        this.schedules = schedules; this.behaviorRepo = behaviorRepo;
        this.seatRepo = seatRepo; this.enc = enc;
    }

    @Override public void run(String... args) {
        // Luôn đảm bảo tài khoản admin và gv tồn tại với mật khẩu chuẩn xác
        users.findByUsername("admin").ifPresentOrElse(
            u -> {
                u.setPassword(enc.encode("admin123"));
                u.setRole("ADMIN");
                u.setFullName("Quản trị viên Hệ thống");
                users.save(u);
            },
            () -> user("admin", "admin123", "ADMIN", "Quản trị viên Hệ thống")
        );

        users.findByUsername("gv").ifPresentOrElse(
            u -> {
                u.setPassword(enc.encode("gv123"));
                u.setRole("TEACHER");
                u.setFullName("Giáo viên Ngoại ngữ");
                users.save(u);
            },
            () -> user("gv", "gv123", "TEACHER", "Giáo viên Ngoại ngữ")
        );

        if (rooms.count() == 0) {
            Room a = rooms.save(new Room(null, "A101", "Phòng Ngoại ngữ Thông minh SmartClass A101", 40));
            Room b = rooms.save(new Room(null, "B202", "Phòng Lab Ngoại ngữ Đa năng B202", 40));
            states.save(new DeviceState(a.getId()));
            states.save(new DeviceState(b.getId()));
            for (int d = 1; d <= 5; d++) {
                sch(a, d, 7, 9, "Tiếng Anh Giao tiếp 4.0", "ThS. Hoàng Thu Thảo");
                sch(a, d, 13, 15, "Luyện kỹ năng Nghe - Nói SmartClass", "TS. Nguyễn Minh Hải");
                sch(b, d, 9, 11, "Tiếng Anh Chuyên ngành CNTT", "ThS. Trần Quang Vinh");
            }
        }

        // Đổi tên các phòng nếu còn nhãn mô phỏng cũ
        rooms.findAll().forEach(r -> {
            if (r.getName() != null && (r.getName().toLowerCase().contains("mo phong") || r.getName().toLowerCase().contains("mô phỏng") || r.getName().contains("A102"))) {
                r.setName("Phòng Lab Ngoại ngữ Đa năng " + r.getCode());
                rooms.save(r);
            }
        });

        seedSampleBehaviors();
        seedSeats();
    }

    private void seedSeats() {
        if (seatRepo.count() >= 40) return;
        seatRepo.deleteAll();
        Room a = rooms.findByCode("A101").orElse(null);
        if (a == null) {
            a = rooms.findAll().stream().findFirst().orElse(null);
        }
        if (a == null) return;

        // 5 hàng x 4 dãy x 2 chỗ/bàn = 40 chỗ ngồi tiêu chuẩn Sao Mai SmartClass
        String[][] students = {
            // Hàng 1
            {"HS01", "Nguyễn Văn An",        "1", "1", "L"},
            {"HS02", "Trần Thị Mai",         "1", "1", "R"},
            {"HS03", "Lê Hoàng Nam",         "1", "2", "L"},
            {"HS04", "Phạm Thu Hà",          "1", "2", "R"},
            {"HS05", "Vũ Minh Đức",          "1", "3", "L"},
            {"HS06", "Đỗ Phương Linh",       "1", "3", "R"},
            {"HS07", "Hoàng Tuấn Kiệt",      "1", "4", "L"},
            {"HS08", "Bùi Thanh Thảo",       "1", "4", "R"},
            // Hàng 2
            {"HS09", "Ngô Gia Huy",          "2", "1", "L"},
            {"HS10", "Đặng Thùy Dung",       "2", "1", "R"},
            {"HS11", "Phan Quốc Bảo",        "2", "2", "L"},
            {"HS12", "Trịnh Minh Châu",      "2", "2", "R"},
            {"HS13", "Lê Hải Đăng",          "2", "3", "L"},
            {"HS14", "Nguyễn Khánh Linh",    "2", "3", "R"},
            {"HS15", "Trần Đình Phúc",       "2", "4", "L"},
            {"HS16", "Võ Thị Ngọc",          "2", "4", "R"},
            // Hàng 3
            {"HS17", "Đinh Công Danh",       "3", "1", "L"},
            {"HS18", "Huỳnh Thị Yến",        "3", "1", "R"},
            {"HS19", "Mai Xuân Trọng",       "3", "2", "L"},
            {"HS20", "Lê Thị Bích Ngọc",     "3", "2", "R"},
            {"HS21", "Phạm Quang Hưng",      "3", "3", "L"},
            {"HS22", "Trần Thị Thanh Tâm",   "3", "3", "R"},
            {"HS23", "Nguyễn Đức Anh",       "3", "4", "L"},
            {"HS24", "Đoàn Thị Hường",       "3", "4", "R"},
            // Hàng 4
            {"HS25", "Vương Gia Bảo",        "4", "1", "L"},
            {"HS26", "Lâm Thị Thu",          "4", "1", "R"},
            {"HS27", "Cao Minh Trí",         "4", "2", "L"},
            {"HS28", "Đặng Ngọc Hân",        "4", "2", "R"},
            {"HS29", "Tạ Quốc Việt",         "4", "3", "L"},
            {"HS30", "Nguyễn Thị Lan",       "4", "3", "R"},
            {"HS31", "Hồ Đức Mạnh",          "4", "4", "L"},
            {"HS32", "Bùi Phương Anh",       "4", "4", "R"},
            // Hàng 5
            {"HS33", "Trần Văn Sơn",         "5", "1", "L"},
            {"HS34", "Lê Thị Hằng",          "5", "1", "R"},
            {"HS35", "Phạm Minh Quân",       "5", "2", "L"},
            {"HS36", "Nguyễn Thị Ánh",       "5", "2", "R"},
            {"HS37", "Đỗ Xuân Hoàng",        "5", "3", "L"},
            {"HS38", "Trịnh Thị Nhung",      "5", "3", "R"},
            {"HS39", "Vũ Thành Long",        "5", "4", "L"},
            {"HS40", "Hoàng Thị Mỹ Linh",    "5", "4", "R"}
        };

        int totalCols = 4;
        int totalRows = 5;

        for (String[] st : students) {
            int row = Integer.parseInt(st[2]);
            int col = Integer.parseInt(st[3]);
            String side = st[4];

            String code = "B" + row + "-D" + col + side;

            double cellW = 1.0 / totalCols;
            double cellH = 1.0 / totalRows;
            double baseX = (col - 1) * cellW;
            double baseY = (row - 1) * cellH;

            double x1, x2;
            if ("L".equals(side)) {
                x1 = baseX;
                x2 = baseX + cellW / 2;
            } else {
                x1 = baseX + cellW / 2;
                x2 = baseX + cellW;
            }

            DeskSeat seat = new DeskSeat(a.getId(), code, st[0], st[1], row, col, side,
                    x1, baseY, x2, baseY + cellH);
            seat.setCurrentAction("Hoc bai");
            seat.setCurrentStatus("studying");
            seatRepo.save(seat);
        }
    }

    private void seedSampleBehaviors() {
        if (behaviorRepo.count() == 0) {
            rooms.findAll().forEach(r -> {
                behaviorRepo.save(new BehaviorLog(r.getId(), 40, 6, 20, 14, 0, 0, 0));
            });
        }
    }

    private void user(String u, String p, String role, String name) {
        AppUser x = new AppUser();
        x.setUsername(u);
        x.setPassword(enc.encode(p));
        x.setRole(role);
        x.setFullName(name);
        users.save(x);
    }

    private void sch(Room r, int day, int from, int to, String subject, String teacher) {
        Schedule s = new Schedule();
        s.setRoomId(r.getId());
        s.setDayOfWeek(day);
        s.setStartTime(LocalTime.of(from, 0));
        s.setEndTime(LocalTime.of(to, 0));
        s.setSubject(subject);
        s.setTeacher(teacher);
        schedules.save(s);
    }
}
