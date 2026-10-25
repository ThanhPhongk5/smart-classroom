/*
 * ==============================================================================
 * HE THONG PHONG HOC THONG MINH SMARTCLASS - IOT CAP DO 5 (IOT LEVEL 5)
 * Nut Dau Cuoi: ESP32 Cam Bien, Man Hinh & Thiet Bi Chap Hanh (End Node)
 * Ket noi: Cap USB Serial truc tiep voi May tinh / Node Dieu Phoi (ai_server.py)
 * Tich hop Web: So lieu tu dong day len Web Dashboard (Spring Boot & Cloud)
 * ==============================================================================
 * 
 * THU VIEN CAN CAI TREN ARDUINO IDE (Tools -> Manage Libraries...):
 *   1. "ArduinoJson" (Boi Benoit Blanchon - Chon v6 hoac v7)
 *   2. "DHT sensor library" (Boi Adafruit)
 *   3. "Adafruit Unified Sensor" (Boi Adafruit)
 *   4. Neu dung LCD 1602 I2C: Cai them "LiquidCrystal I2C" (Boi Frank de Brabander)
 *   5. Neu dung OLED 0.96 inch: Cai them "Adafruit SSD1306" va "Adafruit GFX Library"
 * 
 * SO DO NOI DAY (PINOUT TRUONG DU AN):
 *   - DHT11 / DHT22 (Nhiet do, Do am):   Pin GPIO 4  (VCC -> 3V3/5V, GND -> GND)
 *   - LDR Quang tro (Cam bien anh sang): Pin GPIO 34 (Chan tin hieu Analog)
 *   - PIR HC-SR501 (Cam bien chuyen dong): Pin GPIO 27 (Neu co, hoac de trong)
 *   - Relay / LED Den (Light):           Pin GPIO 25 (Active-LOW: LOW=BAT, HIGH=TAT)
 *   - Relay / LED Quat (Fan):            Pin GPIO 26
 *   - Relay / LED Dieu hoa (AC):         Pin GPIO 33
 *   - Man hinh I2C (LCD 1602 hoac OLED):
 *       + SDA -> Pin GPIO 21
 *       + SCL -> Pin GPIO 22
 *       + VCC -> 5V (hoac 3V3), GND -> GND
 * ==============================================================================
 */

#include <ArduinoJson.h>
#include <DHT.h>
#include <Wire.h>

// ====== 1. CAU HINH LOAI CAM BIEN NHIET DO ======
// Neu cam bien mau XANH DUONG: de DHT11
// Neu cam bien mau TRANG: sua thanh DHT22
#define DHTTYPE DHT11
// #define DHTTYPE DHT22

#define PIN_DHT    4
#define PIN_LDR    34
#define PIN_PIR    27
#define PIN_LIGHT  25
#define PIN_FAN    26
#define PIN_AC     33

#define RELAY_ON   LOW
#define RELAY_OFF  HIGH

// ====== 2. CAU HINH MAN HINH (Chon 1 trong 2 loai man hinh ben duoi) ======
// Bat che do LCD 1602 I2C (Man hinh 16 ky tu x 2 dong kem module I2C 4 chan):
#define ENABLE_LCD_1602
#ifdef ENABLE_LCD_1602
  #include <LiquidCrystal_I2C.h>
  LiquidCrystal_I2C lcd(0x27, 16, 2); // Neu khong len chu thi thu dia chi 0x3F
#endif

// Hoac neu dung man hinh OLED 0.96 inch (128x64 I2C), hay bo comment 2 dong duoi va tat LCD:
// #define ENABLE_OLED_SSD1306
#ifdef ENABLE_OLED_SSD1306
  #include <Adafruit_GFX.h>
  #include <Adafruit_SSD1306.h>
  Adafruit_SSD1306 display(128, 64, &Wire, -1);
#endif

// ====== 3. CAU HINH PHONG HOC ======
const char* ROOM_CODE = "A101";
const unsigned long SEND_INTERVAL = 2500; // Gui du lieu len Web moi 2.5 giay

DHT dht(PIN_DHT, DHTTYPE);
unsigned long lastSend = 0;
unsigned long lastDisplayUpdate = 0;
bool motionLatch = false;

// Trang thai thiet bi hien tai
bool stateLight = false;
bool stateFan   = false;
bool stateAc    = false;

float currentTemp = 26.5;
float currentHum  = 60.0;
float currentLux  = 450.0;

void setRelay(int pin, bool on) {
  digitalWrite(pin, on ? RELAY_ON : RELAY_OFF);
}

float readLux() {
  int raw = analogRead(PIN_LDR); // 0..4095
  // Chuyen doi ADC 12-bit sang dai do lux 0..1000
  float lux = (raw / 4095.0f) * 1000.0f;
  return round(lux * 10.0) / 10.0;
}

// Cap nhat thong so len man hinh LCD / OLED
void updateScreen() {
#ifdef ENABLE_LCD_1602
  lcd.setCursor(0, 0);
  lcd.print(ROOM_CODE);
  lcd.print(":");
  lcd.print(currentTemp, 1);
  lcd.print((char)223); // Ky tu do C
  lcd.print("C ");
  lcd.print(currentHum, 0);
  lcd.print("%  ");

  lcd.setCursor(0, 1);
  lcd.print("L:");
  lcd.print((int)currentLux);
  lcd.print("lx D:");
  lcd.print(stateLight ? "ON " : "OFF");
  lcd.print("Q:");
  lcd.print(stateFan ? "ON" : "--");
#endif

#ifdef ENABLE_OLED_SSD1306
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 0);
  display.print("SMART CLASSROOM ");
  display.println(ROOM_CODE);
  display.println("---------------------");
  display.print("Nhiet do: "); display.print(currentTemp, 1); display.println(" C");
  display.print("Do am:    "); display.print(currentHum, 0); display.println(" %");
  display.print("Anh sang: "); display.print((int)currentLux); display.println(" lux");
  display.print("Den: "); display.print(stateLight ? "ON " : "OFF ");
  display.print("| Quat: "); display.println(stateFan ? "ON" : "OFF");
  display.display();
#endif
}

void setup() {
  Serial.begin(115200);

  // Khoi tao I2C (SDA=21, SCL=22)
  Wire.begin(21, 22);

#ifdef ENABLE_LCD_1602
  lcd.init();
  lcd.backlight();
  lcd.setCursor(0, 0);
  lcd.print("SMART CLASSROOM");
  lcd.setCursor(0, 1);
  lcd.print("Connecting PC...");
#endif

#ifdef ENABLE_OLED_SSD1306
  if (display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    display.clearDisplay();
    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(10, 20);
    display.println("SMART CLASSROOM");
    display.setCursor(10, 36);
    display.println("Connecting PC...");
    display.display();
  }
#endif

  pinMode(PIN_PIR, INPUT);
  for (int p : {PIN_LIGHT, PIN_FAN, PIN_AC}) {
    pinMode(p, OUTPUT);
    setRelay(p, false);
  }

  dht.begin();
  delay(1200);

  // Gui thong bao san sang ve may tinh qua Serial
  Serial.println("{\"status\":\"ESP32_READY\",\"roomCode\":\"A101\",\"mode\":\"IOT_LEVEL_5_SERIAL\"}");
}

// Xu ly lenh Bật / Tắt thiết bị nhận từ Web gửi xuống qua Serial
void processIncomingCommand(const String& jsonStr) {
  StaticJsonDocument<256> doc;
  DeserializationError err = deserializeJson(doc, jsonStr);
  if (err) return;

  bool changed = false;
  if (doc.containsKey("light")) {
    stateLight = doc["light"];
    setRelay(PIN_LIGHT, stateLight);
    changed = true;
  }
  if (doc.containsKey("fan")) {
    stateFan = doc["fan"];
    setRelay(PIN_FAN, stateFan);
    changed = true;
  }
  if (doc.containsKey("ac")) {
    stateAc = doc["ac"];
    setRelay(PIN_AC, stateAc);
    changed = true;
  }

  if (changed) {
    updateScreen();
  }
}

// Gui du lieu cam bien ve may tinh qua Serial de day len Web
void sendTelemetryToCoordinator() {
  float t = dht.readTemperature();
  float h = dht.readHumidity();

  if (!isnan(t)) currentTemp = round(t * 10.0) / 10.0;
  if (!isnan(h)) currentHum  = round(h * 10.0) / 10.0;
  currentLux = readLux();

  StaticJsonDocument<256> doc;
  doc["roomCode"] = ROOM_CODE;
  doc["temperature"] = currentTemp;
  doc["humidity"] = currentHum;
  doc["lux"] = currentLux;
  doc["motion"] = motionLatch;
  doc["lightState"] = stateLight;
  doc["fanState"]   = stateFan;
  doc["acState"]    = stateAc;

  // Reset bien nho chuyen dong
  motionLatch = false;

  // In dong goi JSON tren 1 dong ve cong Serial
  serializeJson(doc, Serial);
  Serial.println();

  // Cap nhat hien thi len man hinh LED / LCD
  updateScreen();
}

void loop() {
  // 1. Doc cam bien chuyen dong PIR (neu co)
  if (digitalRead(PIN_PIR) == HIGH) {
    motionLatch = true;
  }

  // 2. Lang nghe lenh dieu khien tu Web gui xuong qua Serial
  if (Serial.available() > 0) {
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.startsWith("{") && line.endsWith("}")) {
      processIncomingCommand(line);
    }
  }

  // 3. Dinh ky gui telemetry len Web va cap nhat man hinh
  if (millis() - lastSend >= SEND_INTERVAL) {
    lastSend = millis();
    sendTelemetryToCoordinator();
  }

  delay(20);
}
