package com.iot.classroom.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class ActionLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long roomId;
    private String message;
    private String source;
    private LocalDateTime createdAt = LocalDateTime.now();

    public ActionLog() {}
    public ActionLog(Long roomId, String message, String source) {
        this.roomId = roomId; this.message = message; this.source = source;
    }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
