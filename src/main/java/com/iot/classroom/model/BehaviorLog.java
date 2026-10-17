package com.iot.classroom.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "behavior_log")
public class BehaviorLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long roomId;

    private LocalDateTime createdAt;

    private int totalStudents;
    private int handRaising;
    private int reading;
    private int writing;
    private int usingPhone;
    private int bowingHead;
    private int leaningTable;
    private double attentionScore;

    public BehaviorLog() {
        this.createdAt = LocalDateTime.now();
    }

    public BehaviorLog(Long roomId, int totalStudents, int handRaising, int reading, int writing, int usingPhone, int bowingHead, int leaningTable) {
        this.roomId = roomId;
        this.totalStudents = totalStudents;
        this.handRaising = handRaising;
        this.reading = reading;
        this.writing = writing;
        this.usingPhone = usingPhone;
        this.bowingHead = bowingHead;
        this.leaningTable = leaningTable;
        this.createdAt = LocalDateTime.now();
        if (totalStudents > 0) {
            int positive = handRaising + reading + writing;
            this.attentionScore = Math.round((positive * 100.0 / totalStudents) * 10.0) / 10.0;
        } else {
            this.attentionScore = 0.0;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public int getTotalStudents() { return totalStudents; }
    public void setTotalStudents(int totalStudents) { this.totalStudents = totalStudents; }

    public int getHandRaising() { return handRaising; }
    public void setHandRaising(int handRaising) { this.handRaising = handRaising; }

    public int getReading() { return reading; }
    public void setReading(int reading) { this.reading = reading; }

    public int getWriting() { return writing; }
    public void setWriting(int writing) { this.writing = writing; }

    public int getUsingPhone() { return usingPhone; }
    public void setUsingPhone(int usingPhone) { this.usingPhone = usingPhone; }

    public int getBowingHead() { return bowingHead; }
    public void setBowingHead(int bowingHead) { this.bowingHead = bowingHead; }

    public int getLeaningTable() { return leaningTable; }
    public void setLeaningTable(int leaningTable) { this.leaningTable = leaningTable; }

    public double getAttentionScore() { return attentionScore; }
    public void setAttentionScore(double attentionScore) { this.attentionScore = attentionScore; }
}