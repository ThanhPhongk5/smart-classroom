package com.iot.classroom.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class DeviceState {
    @Id private Long roomId;
    private boolean light;
    private boolean fan;
    private boolean ac;
    private int acTemp = 26;
    private boolean auto = true;
    private LocalDateTime lastMotionAt;
    private LocalDateTime lastSeen;

    public DeviceState() {}
    public DeviceState(Long roomId) { this.roomId = roomId; }
    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }
    public boolean isLight() { return light; }
    public void setLight(boolean light) { this.light = light; }
    public boolean isFan() { return fan; }
    public void setFan(boolean fan) { this.fan = fan; }
    public boolean isAc() { return ac; }
    public void setAc(boolean ac) { this.ac = ac; }
    public int getAcTemp() { return acTemp; }
    public void setAcTemp(int acTemp) { this.acTemp = acTemp; }
    public boolean isAuto() { return auto; }
    public void setAuto(boolean auto) { this.auto = auto; }
    public LocalDateTime getLastMotionAt() { return lastMotionAt; }
    public void setLastMotionAt(LocalDateTime lastMotionAt) { this.lastMotionAt = lastMotionAt; }
    public LocalDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(LocalDateTime lastSeen) { this.lastSeen = lastSeen; }
}
