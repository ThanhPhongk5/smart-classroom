package com.iot.classroom.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "desk_seat")
public class DeskSeat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long roomId;

    @Column(nullable = false)
    private String seatCode; // B1-D1L, B1-D1R, B2-D3L... (L=trai, R=phai)

    private String studentCode; // HS01, HS02... (co the null neu chua gan)
    private String studentName; // Nguyen Van An (co the null)

    private int rowNum; // Hang 1, 2, 3, 4, 5
    private int colNum; // Day 1, 2, 3, 4
    private String side; // L = ben trai, R = ben phai cua ban

    // Toa do tren camera (0.0 - 1.0)
    private double normX1;
    private double normY1;
    private double normX2;
    private double normY2;

    private String currentStatus = "absent"; // present, absent, raising_hand, using_phone, bowing_head, studying
    private String currentAction = "";
    private LocalDateTime lastSeen = LocalDateTime.now();
    private int violationCount = 0; // So lan phat hien dung DT hoac ngu
    private int participationCount = 0; // So lan phat bieu

    public DeskSeat() {}

    public DeskSeat(Long roomId, String seatCode, String studentCode, String studentName,
                    int rowNum, int colNum, String side,
                    double normX1, double normY1, double normX2, double normY2) {
        this.roomId = roomId;
        this.seatCode = seatCode;
        this.studentCode = studentCode;
        this.studentName = studentName;
        this.rowNum = rowNum;
        this.colNum = colNum;
        this.side = side;
        this.normX1 = normX1;
        this.normY1 = normY1;
        this.normX2 = normX2;
        this.normY2 = normY2;
        this.currentStatus = (studentCode != null && !studentCode.isEmpty()) ? "studying" : "absent";
        this.currentAction = (studentCode != null && !studentCode.isEmpty()) ? "Hoc bai" : "";
        this.lastSeen = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }

    public String getSeatCode() { return seatCode; }
    public void setSeatCode(String seatCode) { this.seatCode = seatCode; }

    public String getStudentCode() { return studentCode; }
    public void setStudentCode(String studentCode) { this.studentCode = studentCode; }

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }

    public int getRowNum() { return rowNum; }
    public void setRowNum(int rowNum) { this.rowNum = rowNum; }

    public int getColNum() { return colNum; }
    public void setColNum(int colNum) { this.colNum = colNum; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public double getNormX1() { return normX1; }
    public void setNormX1(double normX1) { this.normX1 = normX1; }

    public double getNormY1() { return normY1; }
    public void setNormY1(double normY1) { this.normY1 = normY1; }

    public double getNormX2() { return normX2; }
    public void setNormX2(double normX2) { this.normX2 = normX2; }

    public double getNormY2() { return normY2; }
    public void setNormY2(double normY2) { this.normY2 = normY2; }

    public String getCurrentStatus() { return currentStatus; }
    public void setCurrentStatus(String currentStatus) { this.currentStatus = currentStatus; }

    public String getCurrentAction() { return currentAction; }
    public void setCurrentAction(String currentAction) { this.currentAction = currentAction; }

    public LocalDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(LocalDateTime lastSeen) { this.lastSeen = lastSeen; }

    public int getViolationCount() { return violationCount; }
    public void setViolationCount(int violationCount) { this.violationCount = violationCount; }

    public int getParticipationCount() { return participationCount; }
    public void setParticipationCount(int participationCount) { this.participationCount = participationCount; }
}
