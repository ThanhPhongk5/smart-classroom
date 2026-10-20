package com.iot.classroom.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "students")
public class Student {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String studentCode; // HS01, HS02, ...

    @Column(nullable = false, length = 100)
    private String fullName;

    @Column(length = 30)
    private String className = "12A1"; // Lop 10A1, 11B2, 12A1...

    @Column(length = 10)
    private String gender = "Nam"; // Nam / Nu

    @Column(length = 20)
    private String dateOfBirth = "2008-01-01";

    @Column(length = 20)
    private String phone = "";

    @Column(length = 100)
    private String email = "";

    private Long roomId; // ID phong dang ngoi, null neu chua xep

    @Column(length = 20)
    private String seatCode; // B1-D1L, B1-D1R..., null neu chua xep cho

    @Column(length = 30)
    private String status = "studying"; // studying, unassigned, absent

    private int violationCount = 0; // Phat hien boi Edge AI: dung dien thoai, ngu guc
    private int participationCount = 0; // Phat hien boi Edge AI: gio tay phat bieu

    @Column(length = 500)
    private String notes = "";

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Student() {}

    public Student(String studentCode, String fullName, String className, String gender, 
                   String dateOfBirth, String phone, Long roomId, String seatCode) {
        this.studentCode = studentCode;
        this.fullName = fullName;
        this.className = className;
        this.gender = gender;
        this.dateOfBirth = dateOfBirth;
        this.phone = phone;
        this.roomId = roomId;
        this.seatCode = seatCode;
        this.status = (seatCode != null && !seatCode.isEmpty()) ? "studying" : "unassigned";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStudentCode() { return studentCode; }
    public void setStudentCode(String studentCode) { this.studentCode = studentCode; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(String dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }

    public String getSeatCode() { return seatCode; }
    public void setSeatCode(String seatCode) { this.seatCode = seatCode; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getViolationCount() { return violationCount; }
    public void setViolationCount(int violationCount) { this.violationCount = violationCount; }

    public int getParticipationCount() { return participationCount; }
    public void setParticipationCount(int participationCount) { this.participationCount = participationCount; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
