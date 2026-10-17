package com.iot.classroom.model;

import jakarta.persistence.*;
import java.time.LocalTime;

@Entity
public class Schedule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long roomId;
    private int dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
    private String subject;
    private String teacher;

    public Schedule() {}
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }
    public int getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(int dayOfWeek) { this.dayOfWeek = dayOfWeek; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getTeacher() { return teacher; }
    public void setTeacher(String teacher) { this.teacher = teacher; }
}
