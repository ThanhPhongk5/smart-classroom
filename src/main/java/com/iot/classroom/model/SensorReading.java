package com.iot.classroom.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(indexes = @Index(columnList = "roomId,createdAt"))
public class SensorReading {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long roomId;
    private double temperature;
    private double humidity;
    private double lux;
    private boolean motion;
    private LocalDateTime createdAt = LocalDateTime.now();

    public SensorReading() {}
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public double getHumidity() { return humidity; }
    public void setHumidity(double humidity) { this.humidity = humidity; }
    public double getLux() { return lux; }
    public void setLux(double lux) { this.lux = lux; }
    public boolean isMotion() { return motion; }
    public void setMotion(boolean motion) { this.motion = motion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
