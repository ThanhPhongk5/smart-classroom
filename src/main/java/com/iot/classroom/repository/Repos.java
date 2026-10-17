package com.iot.classroom.repository;

import com.iot.classroom.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

public final class Repos {
    private Repos() {}
    public interface RoomRepo extends JpaRepository<Room, Long> { Optional<Room> findByCode(String code); }
    public interface ReadingRepo extends JpaRepository<SensorReading, Long> {
        Optional<SensorReading> findFirstByRoomIdOrderByCreatedAtDesc(Long roomId);
        List<SensorReading> findByRoomIdAndCreatedAtAfterOrderByCreatedAtAsc(Long roomId, LocalDateTime after);
        void deleteByCreatedAtBefore(LocalDateTime t);
    }
    public interface StateRepo extends JpaRepository<DeviceState, Long> {}
    public interface ScheduleRepo extends JpaRepository<Schedule, Long> {
        List<Schedule> findByRoomIdOrderByDayOfWeekAscStartTimeAsc(Long roomId);
        List<Schedule> findByRoomIdAndDayOfWeek(Long roomId, int dayOfWeek);
    }
    public interface UserRepo extends JpaRepository<AppUser, Long> { Optional<AppUser> findByUsername(String u); }
    public interface LogRepo extends JpaRepository<ActionLog, Long> { List<ActionLog> findTop50ByRoomIdOrderByCreatedAtDesc(Long roomId); }
    public interface BehaviorRepo extends JpaRepository<BehaviorLog, Long> {
        Optional<BehaviorLog> findFirstByRoomIdOrderByCreatedAtDesc(Long roomId);
        List<BehaviorLog> findTop30ByRoomIdOrderByCreatedAtDesc(Long roomId);
        List<BehaviorLog> findByRoomIdAndCreatedAtAfterOrderByCreatedAtAsc(Long roomId, LocalDateTime after);
    }
    public interface SeatRepo extends JpaRepository<DeskSeat, Long> {
        List<DeskSeat> findByRoomIdOrderByRowNumAscColNumAsc(Long roomId);
        Optional<DeskSeat> findByRoomIdAndSeatCode(Long roomId, String seatCode);
        @Transactional
        void deleteByRoomId(Long roomId);
    }
}
