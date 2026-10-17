package com.iot.classroom.controller;

import com.iot.classroom.model.ActionLog;
import com.iot.classroom.model.AppUser;
import com.iot.classroom.repository.Repos;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final Repos.UserRepo userRepo;
    private final Repos.LogRepo logRepo;
    private final PasswordEncoder passwordEncoder;

    public UserController(Repos.UserRepo userRepo, Repos.LogRepo logRepo, PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.logRepo = logRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public List<Map<String, Object>> getAllUsers() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AppUser u : userRepo.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("fullName", u.getFullName());
            m.put("role", u.getRole());
            list.add(m);
        }
        return list;
    }

    public record UserCreateReq(String username, String password, String fullName, String role) {}

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody UserCreateReq req, Authentication auth) {
        if (req.username() == null || req.username().trim().isEmpty() ||
            req.password() == null || req.password().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Tên đăng nhập và mật khẩu không được để trống!"));
        }
        String username = req.username().trim().toLowerCase();
        if (userRepo.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Tên đăng nhập '" + username + "' đã tồn tại!"));
        }
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(req.password().trim()));
        u.setFullName(req.fullName() != null && !req.fullName().trim().isEmpty() ? req.fullName().trim() : username);
        String role = (req.role() != null && req.role().equalsIgnoreCase("ADMIN")) ? "ADMIN" : "TEACHER";
        u.setRole(role);
        userRepo.save(u);

        String creator = auth != null ? auth.getName() : "ADMIN";
        logRepo.save(new ActionLog(1L, "Đã tạo tài khoản mới: " + username + " (" + role + ")", creator));

        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Đã tạo tài khoản người dùng thành công!",
            "user", Map.of("id", u.getId(), "username", u.getUsername(), "fullName", u.getFullName(), "role", u.getRole())
        ));
    }

    public record UserUpdateReq(String fullName, String role, String newPassword) {}

    @PutMapping("/{id}")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody UserUpdateReq req, Authentication auth) {
        AppUser u = userRepo.findById(id).orElse(null);
        if (u == null) return ResponseEntity.notFound().build();

        if (req.fullName() != null && !req.fullName().trim().isEmpty()) {
            u.setFullName(req.fullName().trim());
        }
        if (req.role() != null && !req.role().trim().isEmpty()) {
            u.setRole(req.role().equalsIgnoreCase("ADMIN") ? "ADMIN" : "TEACHER");
        }
        if (req.newPassword() != null && !req.newPassword().trim().isEmpty()) {
            u.setPassword(passwordEncoder.encode(req.newPassword().trim()));
        }
        userRepo.save(u);

        String updater = auth != null ? auth.getName() : "ADMIN";
        logRepo.save(new ActionLog(1L, "Đã cập nhật thông tin tài khoản: " + u.getUsername(), updater));

        return ResponseEntity.ok(Map.of("status", "success", "message", "Đã cập nhật tài khoản thành công!"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id, Authentication auth) {
        AppUser u = userRepo.findById(id).orElse(null);
        if (u == null) return ResponseEntity.notFound().build();

        if (auth != null && u.getUsername().equalsIgnoreCase(auth.getName())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Bạn không thể tự xóa tài khoản đang đăng nhập!"));
        }
        if ("admin".equalsIgnoreCase(u.getUsername())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Không thể xóa tài khoản Quản trị viên mặc định (admin)!"));
        }

        userRepo.delete(u);
        String deleter = auth != null ? auth.getName() : "ADMIN";
        logRepo.save(new ActionLog(1L, "Đã xóa tài khoản: " + u.getUsername(), deleter));

        return ResponseEntity.ok(Map.of("status", "success", "message", "Đã xóa tài khoản " + u.getUsername()));
    }
}
