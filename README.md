# Hệ thống Lớp học thông minh (ESP32 + Spring Boot)

## 1. Kiến trúc
```
ESP32 (DHT22, LDR, PIR, 3 relay) --HTTP POST JSON mỗi 5s--> Spring Boot (REST + H2/JPA) <--REST/JSON, polling 3s-- Trình duyệt (Dashboard)
                                 <-- phản hồi = lệnh bật/tắt đèn, quạt, điều hòa --
```
Mỗi lần ESP32 gửi số liệu, server lưu vào DB, chạy logic tự động và trả về trạng thái thiết bị mong muốn; ESP32 áp dụng ngay lên relay. Không cần MQTT nên dễ triển khai.

## 2. Chức năng
- Giám sát: nhiệt độ, độ ẩm, ánh sáng, có người/không, trạng thái ESP32 online/offline, biểu đồ 1/6/24 giờ.
- Điều khiển: đèn, quạt, điều hòa (bật/tắt + đặt nhiệt độ 16–30°C).
- **Chế độ tự động**: đang có tiết học hoặc PIR thấy người → đèn bật khi lux < 300; quạt khi ≥ 27°C; điều hòa khi ≥ 30°C (tắt khi < 28°C); phòng trống > 10 phút → tắt hết (tiết kiệm điện). Bấm điều khiển tay sẽ tự chuyển sang thủ công.
- Quản lý lịch học theo tuần, chặn trùng giờ trong cùng phòng.
- Cảnh báo nhiệt độ > 35°C, mất kết nối ESP32.
- Nhật ký thao tác (ai/AUTO đã bật tắt gì, lúc nào).
- Đăng nhập, phân quyền: ADMIN (xóa được lịch), TEACHER. Nhiều phòng. Mô phỏng ESP32 cho phòng A102 để demo khi chưa có mạch.

## 3. Chạy server
Yêu cầu JDK 17+ và Maven.
```
mvn spring-boot:run
```
Mở http://localhost:8080 — tài khoản `admin/admin123` hoặc `gv/gv123`.
CSDL H2 lưu ở thư mục `data/`. Muốn dùng MySQL: thêm dependency `mysql-connector-j` và đổi `spring.datasource.*` trong `application.properties`.

## 4. Nối mạch ESP32 (sketch: `esp32/smart_classroom.ino`)
| Linh kiện | Chân ESP32 | Ghi chú |
|---|---|---|
| DHT22 DATA | GPIO4 | VCC 3.3V, pull-up 10k |
| LDR | GPIO34 | LDR nối 3.3V, điện trở 10k xuống GND, lấy điểm giữa |
| PIR HC-SR501 OUT | GPIO27 | VCC 5V |
| Relay 1 (đèn) | GPIO25 | |
| Relay 2 (quạt) | GPIO26 | |
| Relay 3 (điều hòa) | GPIO33 | Demo: dùng LED/relay; thực tế dùng IR LED + IRremoteESP8266 |

Sửa `WIFI_SSID`, `WIFI_PASS`, `SERVER_URL` (IP máy chạy server, cùng mạng WiFi), `ROOM_CODE`. ESP32 và máy tính phải cùng mạng; tắt tường lửa chặn cổng 8080 nếu không kết nối được.

## 5. API chính
| Method | URL | Mô tả |
|---|---|---|
| POST | /api/device/telemetry | ESP32 gửi số liệu (header `X-Device-Key`), trả về lệnh |
| GET | /api/rooms, /api/rooms/{id}/status, /history?hours=, /logs | Dữ liệu cho web |
| POST | /api/rooms/{id}/control | `{light,fan,ac,acTemp,auto}` |
| GET/POST/DELETE | /api/schedules | Lịch học (DELETE: chỉ ADMIN) |

Test không cần mạch:
```
curl -X POST localhost:8080/api/device/telemetry -H "Content-Type: application/json" -H "X-Device-Key: esp32-secret-2026" \
 -d '{"roomCode":"A101","temperature":31.5,"humidity":65,"lux":120,"motion":true}'
```

## 6. Hướng mở rộng
MQTT thay HTTP, WebSocket thay polling, OTA, đo điện năng (PZEM), điểm danh RFID, báo Telegram khi cảnh báo.
