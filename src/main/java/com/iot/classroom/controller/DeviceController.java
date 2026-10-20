package com.iot.classroom.controller;

import com.iot.classroom.model.*;
import com.iot.classroom.repository.Repos;
import com.iot.classroom.service.AutomationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController @RequestMapping("/api/device")
public class DeviceController {
    private final AutomationService automation;
    private final Repos.RoomRepo rooms;
    private final Repos.BehaviorRepo behaviorRepo;
    private final Repos.SeatRepo seatRepo;
    private final Repos.StudentRepo studentRepo;
    private final Repos.LogRepo logs;
    @Value("${app.device-key}") private String deviceKey;

    public record CameraCommand(
        boolean active,
        String roomCode,
        boolean demoMode,
        String demoSeat,
        long timestamp
    ) {}

    private static final Map<String, CameraCommand> roomCommands = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> roomAiStatus = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> roomFrames = new ConcurrentHashMap<>();
    private static final Map<String, Long> roomFrameTimestamps = new ConcurrentHashMap<>();
    private static final Map<String, Long> roomHeartbeats = new ConcurrentHashMap<>();

    public DeviceController(AutomationService automation, Repos.RoomRepo rooms,
                            Repos.BehaviorRepo behaviorRepo, Repos.SeatRepo seatRepo,
                            Repos.StudentRepo studentRepo, Repos.LogRepo logs) {
        this.automation = automation;
        this.rooms = rooms;
        this.behaviorRepo = behaviorRepo;
        this.seatRepo = seatRepo;
        this.studentRepo = studentRepo;
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

    // --- Multi-room Remote camera command & status API ---

    @PostMapping("/camera-command")
    public ResponseEntity<?> setCameraCommand(@RequestBody Map<String, Object> req) {
        boolean active = Boolean.TRUE.equals(req.get("active"));
        String roomCode = req.getOrDefault("roomCode", "A101").toString().trim().toUpperCase();
        boolean demoMode = !Boolean.FALSE.equals(req.get("demoMode"));
        String demoSeat = req.getOrDefault("demoSeat", "B1-D1L").toString();

        CameraCommand cmd = new CameraCommand(active, roomCode, demoMode, demoSeat, System.currentTimeMillis());
        roomCommands.put(roomCode, cmd);
        return ResponseEntity.ok(Map.of("status", "success", "room", roomCode, "command", cmd));
    }

    @GetMapping("/camera-command")
    public ResponseEntity<?> getCameraCommand(
            @RequestParam(value = "cmd", required = false) String cmd,
            @RequestParam(value = "room", required = false, defaultValue = "A101") String room) {
        String r = (room != null && !room.isBlank()) ? room.trim().toUpperCase() : "A101";
        if ("start_cam".equalsIgnoreCase(cmd) || "start".equalsIgnoreCase(cmd)) {
            roomCommands.put(r, new CameraCommand(true, r, true, "B1-D1L", System.currentTimeMillis()));
        } else if ("stop".equalsIgnoreCase(cmd)) {
            roomCommands.put(r, new CameraCommand(false, r, true, "B1-D1L", System.currentTimeMillis()));
        }
        CameraCommand current = roomCommands.getOrDefault(r, new CameraCommand(false, r, true, "B1-D1L", 0));
        return ResponseEntity.ok(current);
    }

    @PostMapping("/camera-heartbeat")
    public ResponseEntity<?> cameraHeartbeat(
            @RequestHeader(value = "X-Device-Key", required = false) String key,
            @RequestBody Map<String, Object> status) {
        if (!deviceKey.equals(key)) return ResponseEntity.status(401).body(Map.of("message", "Sai device key"));
        String roomCode = status.getOrDefault("room", status.getOrDefault("roomCode", "A101")).toString().trim().toUpperCase();
        roomAiStatus.put(roomCode, status);
        long now = System.currentTimeMillis();
        roomHeartbeats.put(roomCode, now);
        CameraCommand cmd = roomCommands.getOrDefault(roomCode, new CameraCommand(false, roomCode, true, "B1-D1L", 0));
        return ResponseEntity.ok(Map.of("status", "success", "room", roomCode, "command", cmd));
    }

    @GetMapping("/camera-status")
    public ResponseEntity<?> getCameraStatus(@RequestParam(value = "room", required = false, defaultValue = "A101") String room) {
        String r = (room != null && !room.isBlank()) ? room.trim().toUpperCase() : "A101";
        long lastHb = roomHeartbeats.getOrDefault(r, 0L);
        boolean isOnline = (System.currentTimeMillis() - lastHb) < 7000;
        Map<String, Object> res = new HashMap<>(roomAiStatus.getOrDefault(r, Map.of()));
        CameraCommand cmd = roomCommands.getOrDefault(r, new CameraCommand(false, r, true, "B1-D1L", 0));
        res.put("room", r);
        res.put("online", isOnline);
        res.put("commandActive", cmd.active());
        boolean running = isOnline && (Boolean.TRUE.equals(res.get("running")) || Boolean.TRUE.equals(res.get("isRunning")));
        res.put("running", running);
        res.put("isRunning", running);
        res.put("active", cmd.active());
        return ResponseEntity.ok(res);
    }

    @PostMapping(value = "/camera-frame", consumes = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<?> uploadFrame(
            @RequestHeader(value = "X-Device-Key", required = false) String key,
            @RequestParam(value = "room", required = false, defaultValue = "A101") String room,
            @RequestBody byte[] frameData) {
        if (!deviceKey.equals(key)) return ResponseEntity.status(401).body(Map.of("message", "Sai device key"));
        String r = (room != null && !room.isBlank()) ? room.trim().toUpperCase() : "A101";
        roomFrames.put(r, frameData);
        long now = System.currentTimeMillis();
        roomFrameTimestamps.put(r, now);
        roomHeartbeats.put(r, now);
        return ResponseEntity.ok(Map.of("status", "success", "room", r));
    }

    @GetMapping(value = "/camera-frame", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> getLatestFrame(@RequestParam(value = "room", required = false, defaultValue = "A101") String room) {
        String r = (room != null && !room.isBlank()) ? room.trim().toUpperCase() : "A101";
        byte[] frame = roomFrames.get(r);
        long lastTime = roomFrameTimestamps.getOrDefault(r, 0L);
        if (frame == null || (System.currentTimeMillis() - lastTime) > 8000) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .body(frame);
    }

    @GetMapping(value = "/camera-stream")
    public ResponseEntity<StreamingResponseBody> streamCamera(@RequestParam(value = "room", required = false, defaultValue = "A101") String room) {
        final String r = (room != null && !room.isBlank()) ? room.trim().toUpperCase() : "A101";
        StreamingResponseBody responseBody = outputStream -> {
            long lastSent = 0;
            byte[] boundary = "\r\n--frame\r\nContent-Type: image/jpeg\r\n\r\n".getBytes(StandardCharsets.UTF_8);
            byte[] endBoundary = "\r\n".getBytes(StandardCharsets.UTF_8);

            try {
                while (!Thread.currentThread().isInterrupted()) {
                    byte[] frame = roomFrames.get(r);
                    long frameTime = roomFrameTimestamps.getOrDefault(r, 0L);
                    if (frame != null && frameTime > lastSent && (System.currentTimeMillis() - frameTime) < 8000) {
                        lastSent = frameTime;
                        outputStream.write(boundary);
                        outputStream.write(frame);
                        outputStream.write(endBoundary);
                        outputStream.flush();
                    }
                    Thread.sleep(30);
                }
            } catch (Exception ignored) {
                // Client disconnected
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "multipart/x-mixed-replace; boundary=frame")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .header("X-Accel-Buffering", "no")
                .body(responseBody);
    }

    // --- Client Camera (Web browser / Phone getUserMedia) processing ---
    private static volatile byte[] latestClientFrame = null;
    private static volatile long lastClientFrameTime = 0;
    private static volatile Map<String, Object> latestClientAnalysis = new ConcurrentHashMap<>();

    @PostMapping(value = "/client-frame", consumes = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<?> uploadClientFrame(@RequestBody byte[] frameData) {
        latestClientFrame = frameData;
        lastClientFrameTime = System.currentTimeMillis();
        Map<String, Object> res = new HashMap<>(latestClientAnalysis);
        res.put("status", "success");
        long ts = Long.parseLong(latestClientAnalysis.getOrDefault("timestamp", "0").toString());
        res.put("hasAnalysis", !latestClientAnalysis.isEmpty() && (System.currentTimeMillis() - ts < 4000));
        return ResponseEntity.ok(res);
    }

    @GetMapping(value = "/client-frame", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> getClientFrame() {
        if (latestClientFrame == null || (System.currentTimeMillis() - lastClientFrameTime) > 6000) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .body(latestClientFrame);
    }

    @PostMapping("/client-analysis")
    public ResponseEntity<?> saveClientAnalysis(
            @RequestHeader(value = "X-Device-Key", required = false) String key,
            @RequestBody Map<String, Object> analysis) {
        if (!deviceKey.equals(key)) return ResponseEntity.status(401).body(Map.of("message", "Sai device key"));
        analysis.put("timestamp", System.currentTimeMillis());
        latestClientAnalysis = new ConcurrentHashMap<>(analysis);

        // Cap nhat truc tiep thong tin hoc sinh vao database
        try {
            String roomCode = analysis.getOrDefault("roomCode", "A101").toString();
            Room room = rooms.findByCode(roomCode).orElse(null);
            if (room != null) {
                int total = Integer.parseInt(analysis.getOrDefault("totalStudents", 0).toString());
                Object rawStats = analysis.get("stats");
                if (rawStats instanceof Map map) {
                    int hand = map.get("0") != null ? Integer.parseInt(map.get("0").toString()) : 0;
                    int reading = map.get("1") != null ? Integer.parseInt(map.get("1").toString()) : 0;
                    int writing = map.get("2") != null ? Integer.parseInt(map.get("2").toString()) : 0;
                    int phone = map.get("3") != null ? Integer.parseInt(map.get("3").toString()) : 0;
                    int bowing = map.get("4") != null ? Integer.parseInt(map.get("4").toString()) : 0;
                    int leaning = map.get("5") != null ? Integer.parseInt(map.get("5").toString()) : 0;
                    behaviorRepo.save(new BehaviorLog(room.getId(), total, hand, reading, writing, phone, bowing, leaning));
                }
                String seatCode = analysis.getOrDefault("seatCode", "B1-D1L").toString();
                String status = analysis.getOrDefault("status", "studying").toString();
                String action = analysis.getOrDefault("action", "Hoc bai").toString();
                seatRepo.findByRoomIdAndSeatCode(room.getId(), seatCode).ifPresent(seat -> {
                    seat.setCurrentStatus(status);
                    seat.setCurrentAction(action);
                    seat.setLastSeen(LocalDateTime.now());
                    if ("using_phone".equals(status)) {
                        seat.setViolationCount(seat.getViolationCount() + 1);
                        logs.save(new ActionLog(room.getId(), "[CẢNH BÁO AI] " + seat.getStudentName() + " (" + seat.getSeatCode() + ") dùng điện thoại", "AI_CAMERA"));
                    } else if ("raising_hand".equals(status)) {
                        seat.setParticipationCount(seat.getParticipationCount() + 1);
                    }
                    seatRepo.save(seat);

                    // Dong bo vao ho so hoc sinh
                    if (seat.getStudentCode() != null && !seat.getStudentCode().isBlank()) {
                        studentRepo.findByStudentCode(seat.getStudentCode()).ifPresent(student -> {
                            if ("using_phone".equals(status)) {
                                student.setViolationCount(student.getViolationCount() + 1);
                            } else if ("raising_hand".equals(status)) {
                                student.setParticipationCount(student.getParticipationCount() + 1);
                            }
                            studentRepo.save(student);
                        });
                    }
                });
            }
        } catch (Exception ignored) {}

        return ResponseEntity.ok(Map.of("status", "success"));
    }

    @GetMapping("/client-analysis")
    public ResponseEntity<?> getClientAnalysis() {
        return ResponseEntity.ok(latestClientAnalysis);
    }
}
