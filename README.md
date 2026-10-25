# Smart Classroom – Hệ thống Lớp học Thông minh IoT

Hệ thống giám sát và điều khiển lớp học thông minh tích hợp **ESP32 thật**, **AI Camera YOLO11**, và **Web Dashboard** triển khai trên cloud Render.

---

## 1. Kiến trúc hệ thống

```
                         ┌─────────────────────────────┐
                         │   ESP32 (thật – Phòng A101)  │
                         │  DHT11 · LDR · OLED SSD1306  │
                         │   LED GPIO2 · WiFi HTTPS      │
                         └────────────┬────────────────-┘
                                      │ HTTP POST JSON (3s)
                         ┌────────────▼────────────────-┐
                         │    AI Coordinator (PC)        │
                         │  ai_server.py (YOLO11 + Flask)│
                         │  · Đọc Serial ESP32           │
                         │  · Nhận khung hình camera     │
                         │  · Phát hiện hành vi HS       │
                         │  · Đẩy dữ liệu lên Cloud      │
                         └────────────┬────────────────-┘
                                      │ REST / JSON
                         ┌────────────▼────────────────-┐
                         │     Spring Boot (Cloud Render) │
                         │  REST API · JPA · MySQL TiDB   │
                         │  AutomationService (tự động)   │
                         └────────────┬────────────────-┘
                                      │ polling 3s
                         ┌────────────▼────────────────-┐
                         │   Trình duyệt – Web Dashboard  │
                         │   HTML + Vanilla JS + Chart.js  │
                         └─────────────────────────────-┘
```

Mỗi lần ESP32 gửi telemetry, server lưu vào DB, chạy AutomationService và trả về lệnh bật/tắt thiết bị ngay trong body phản hồi; ESP32 áp dụng lệnh lên LED. Không cần MQTT, dễ triển khai.

---

## 2. Tính năng chính

### Giám sát môi trường
- Đọc và hiển thị: nhiệt độ, độ ẩm (DHT11), cường độ ánh sáng (LDR → lux), trạng thái ESP32 online/offline.
- OLED SSD1306 128×64 hiển thị trực tiếp tại thiết bị: nhiệt độ, độ ẩm, ánh sáng (% + lux), tín hiệu WiFi, trạng thái đèn LED `D:ON / D:--`, đồng bộ web.
- Biểu đồ lịch sử môi trường 1/6/24 giờ (Chart.js) với ba dataset: Nhiệt độ, Độ ẩm, Ánh sáng.

### Điều khiển thiết bị
- **Đèn LED** (GPIO 2): bật/tắt thủ công hoặc tự động theo lux.
- **Quạt thông gió** và **Điều hòa**: điều khiển thủ công qua giao diện web; icon SVG động với hiệu ứng xoay/phát sáng trực quan.
- Chuyển đổi giữa **chế độ thủ công** và **chế độ tự động** trực tiếp từ web.

### Chế độ tự động (AutomationService)
Kích hoạt bằng nút `Tự động` trên web. Logic dựa thuần vào cảm biến thực của ESP32:
| Điều kiện | Hành động |
|---|---|
| Ánh sáng < 500 lux | Bật Đèn LED |
| Nhiệt độ ≥ 30°C (hoặc đang bật AC và ≥ 28°C) | Bật Điều hòa, tắt Quạt |
| Nhiệt độ 27–30°C | Bật Quạt thông gió |
| Nhiệt độ < 27°C | Tắt cả Quạt và Điều hòa |

### AI Camera – Phân tích hành vi học sinh
- **YOLO11n** (Object Detection): phát hiện điện thoại, laptop, sách vở, ngủ gật.
- **YOLOv11n-pose** (Pose Estimation): ước tính tư thế học sinh.
- **bestYolo11n.pt**: mô hình tùy chỉnh phân loại hành vi (tập trung / mất tập trung).
- Giao diện web hiển thị: điểm tập trung, biểu đồ phân phối hành vi, sơ đồ chỗ ngồi cập nhật thời gian thực.
- Camera chạy trên PC; khung hình gửi lên Cloud qua API `/api/device/frame`.

### Quản lý học sinh & sơ đồ chỗ ngồi
- Danh sách học sinh với tìm kiếm, lọc lớp/trạng thái.
- Phân bổ học sinh vào chỗ ngồi cụ thể (hàng–bàn–trái/phải).
- Đổi cấu hình sơ đồ bàn ghế theo số hàng, số cột, số chỗ/bàn.

### Quản lý lịch học & người dùng
- Lịch học theo tuần, chống trùng giờ trong cùng phòng.
- Phân quyền: `ADMIN` (toàn quyền) và `TEACHER` (xem & điều khiển).
- Nhiều phòng học (A101 thật + A102 mô phỏng để demo).

### Nhật ký hệ thống
- Ghi nhận mọi sự kiện: ai bật/tắt thiết bị gì, lúc nào, AUTO hay tay.

---

## 3. Cấu trúc dự án

```
smart-classroom/
├── esp32/
│   └── smart_classroom.ino       # Firmware ESP32 (DHT11 + LDR + OLED + LED)
├── ai_service/
│   ├── ai_server.py              # Node Điều phối AI (Flask + YOLO11 + Serial)
│   ├── ai_monitor.py             # Module đồng bộ dữ liệu AI lên Backend
│   ├── run_ai.bat                # Script khởi động nhanh (Windows)
│   ├── yolo11n.pt                # YOLO11n – phát hiện đồ vật
│   ├── yolo11n-pose.pt           # YOLO11n-pose – ước tính tư thế
│   ├── bestYolo11n.pt            # Mô hình phân loại hành vi tùy chỉnh
│   └── requirements.txt
├── src/main/
│   ├── java/com/iot/classroom/
│   │   ├── config/
│   │   │   ├── DataSeeder.java   # Seed dữ liệu mẫu + chuẩn hóa tên phòng
│   │   │   └── SecurityConfig.java
│   │   ├── controller/
│   │   │   ├── RoomController.java      # API phòng học, lịch sử, điều khiển
│   │   │   ├── DeviceController.java    # API telemetry ESP32, camera AI
│   │   │   ├── ScheduleController.java
│   │   │   ├── StudentController.java
│   │   │   └── UserController.java
│   │   ├── service/
│   │   │   └── AutomationService.java   # Logic tự động + @Scheduled
│   │   └── model/, repository/
│   └── resources/static/
│       ├── index.html            # Giao diện Dashboard (dark mode, glassmorphism)
│       ├── app.js                # Toàn bộ logic frontend
│       └── login.html
└── pom.xml
```

---

## 4. Yêu cầu phần cứng (ESP32 thật)

| Linh kiện | Chân ESP32 | Ghi chú |
|---|---|---|
| **DHT11** DATA | GPIO 4 | VCC 3.3V |
| **LDR** (quang trở) | GPIO 34 | LDR nối 3.3V, điện trở 10kΩ xuống GND, lấy điểm giữa |
| **OLED SSD1306** SDA | GPIO 21 | I2C 128×64, địa chỉ 0x3C |
| **OLED SSD1306** SCL | GPIO 22 | |
| **LED** (đèn trạng thái) | GPIO 2 | LED tích hợp ESP32 hoặc LED ngoài + điện trở 220Ω |

> **Lưu ý:** Hệ thống không dùng cảm biến chuyển động PIR. Chế độ tự động hoạt động dựa thuần vào nhiệt độ (DHT11) và ánh sáng (LDR).

---

## 5. Cài đặt và chạy

### Server Spring Boot (Local)
Yêu cầu JDK 21+ và Maven 3.9+.
```bash
mvn spring-boot:run
```
Truy cập **http://localhost:8080** — tài khoản: `admin / admin123` hoặc `gv / gv123`.

Mặc định dùng MySQL cục bộ. Cấu hình trong `application.properties`:
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/smart_classroom?createDatabaseIfNotExist=true
spring.datasource.username=root
spring.datasource.password=root
```

### Server triển khai Cloud
URL công khai: **https://smart-classroom-pyvl.onrender.com**

### Firmware ESP32
1. Mở `esp32/smart_classroom.ino` bằng Arduino IDE.
2. Cài thư viện: `Adafruit SSD1306`, `Adafruit GFX`, `DHT sensor library`.
3. Sửa các hằng số ở đầu file:
```cpp
const char* ssid      = "TEN_WIFI_CUA_BAN";
const char* password  = "MAT_KHAU_WIFI";
const char* serverUrl = "https://smart-classroom-pyvl.onrender.com/api/device/telemetry";
const char* roomCode  = "A101";
```
4. Nạp vào board ESP32, mở Serial Monitor (115200 baud) để kiểm tra.

### Node Điều phối AI (PC)
```bash
cd ai_service
pip install -r requirements.txt
# Khởi động (tự tìm cổng COM của ESP32)
python ai_server.py --room A101
# Hoặc dùng script Windows
run_ai.bat
```
Node này: đọc Serial từ ESP32, nhận khung hình camera, chạy YOLO, đẩy dữ liệu lên Cloud.

---

## 6. API chính

| Method | Endpoint | Mô tả |
|---|---|---|
| `POST` | `/api/device/telemetry` | ESP32/Coordinator gửi dữ liệu cảm biến (header `X-Device-Key`) |
| `GET` | `/api/rooms` | Danh sách phòng học |
| `GET` | `/api/rooms/{id}/status` | Trạng thái thiết bị + cảm biến mới nhất |
| `GET` | `/api/rooms/{id}/history?hours=` | Lịch sử cảm biến theo giờ |
| `POST` | `/api/rooms/{id}/control` | Điều khiển: `{light, fan, ac, acTemp, auto}` |
| `PUT` | `/api/rooms/{id}` | Cập nhật tên/sức chứa phòng (ADMIN) |
| `GET/POST/DELETE` | `/api/schedules` | Quản lý lịch học |
| `POST` | `/api/device/frame/{roomCode}` | AI Node gửi khung hình camera |
| `GET` | `/api/device/ai-status/{roomCode}` | Trạng thái AI + hành vi học sinh |
| `GET/POST` | `/api/students` | Quản lý danh sách học sinh |
| `GET` | `/api/rooms/{id}/seats` | Sơ đồ chỗ ngồi |

**Test nhanh không cần mạch:**
```bash
curl -X POST https://smart-classroom-pyvl.onrender.com/api/device/telemetry \
  -H "Content-Type: application/json" \
  -H "X-Device-Key: esp32-secret-2026" \
  -d '{"roomCode":"A101","temperature":31.5,"humidity":65,"lux":120,"motion":false}'
```

---

## 7. Hướng mở rộng
- MQTT thay HTTP polling để giảm độ trễ.
- WebSocket thay polling web.
- OTA (Over-the-Air) cập nhật firmware ESP32.
- Đo điện năng tiêu thụ (PZEM-004T).
- Điểm danh RFID/Face Recognition.
- Thông báo Telegram/Email khi có cảnh báo nhiệt độ hoặc mất kết nối.
- Tích hợp IR LED để điều khiển điều hòa thực qua hồng ngoại (IRremoteESP8266).
