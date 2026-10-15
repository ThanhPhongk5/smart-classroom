package com.iot.classroom.model;

import jakarta.persistence.*;

@Entity
public class Room {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;
    private String name;
    private int capacity;

    private int rowCount = 5;       // So hang ban (mac dinh 5)
    private int colCount = 4;       // So day ban (mac dinh 4)
    private int seatsPerDesk = 2;   // So ban/ban: 1 hoac 2 (mac dinh ban doi 2 ban)

    public Room() {}

    public Room(Long id, String code, String name, int capacity) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.capacity = capacity;
        this.rowCount = 5;
        this.colCount = 4;
        this.seatsPerDesk = 2;
    }

    public Room(Long id, String code, String name, int capacity, int rowCount, int colCount, int seatsPerDesk) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.capacity = capacity;
        this.rowCount = rowCount;
        this.colCount = colCount;
        this.seatsPerDesk = seatsPerDesk;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }

    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }

    public int getColCount() { return colCount; }
    public void setColCount(int colCount) { this.colCount = colCount; }

    public int getSeatsPerDesk() { return seatsPerDesk; }
    public void setSeatsPerDesk(int seatsPerDesk) { this.seatsPerDesk = seatsPerDesk; }
}
