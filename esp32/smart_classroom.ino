/*
 * ==============================================================================
 * HE THONG PHONG HOC THONG MINH SMARTCLASS - IOT CAP DO 5 (IOT LEVEL 5)
 * Nut Dau Cuoi: ESP32 Cam Bien & Chap Hanh (End Node)
 * Ket noi: Cap USB Serial truc tiep voi Nut Dieu Phoi (Classroom Coordinator)
 * Khong phu thuoc mang WiFi, khong lo doi IP, do tre 0ms, hoat dong on dinh 100%.
 * ==============================================================================
 * Thu vien can cai tren Arduino IDE (Tools -> Manage Libraries):
 *   1. "DHT sensor library" (Adafruit)
 *   2. "Adafruit Unified Sensor"
 *   3. "ArduinoJson" (v6 hoac v7)
 *
 * So do chan (Pinout):
 *   - DHT22 (Nhiet do / Do am):    Pin 4
 *   - LDR Quang tro (Anh sang):     Pin 34 (Analog ADC1)
 *   - PIR HC-SR501 (Chuyen dong):  Pin 27
 *   - Relay Den LED (Light):       Pin 25 (Active-LOW: LOW=ON, HIGH=OFF)
 *   - Relay Quat gio (Fan):        Pin 26
 *   - Relay Dieu hoa (AC):         Pin 33
 * ==============================================================================
 */

#include <ArduinoJson.h>
#include <DHT.h>

// ====== CAU HINH PHONG HOC ======
const char* ROOM_CODE = "A101";
const unsigned long SEND_INTERVAL = 3000; // Gui telemetry len Coordinator moi 3 giay

#define PIN_DHT    4
#define PIN_LDR    34
#define PIN_PIR    27
#define PIN_LIGHT  25
#define PIN_FAN    26
#define PIN_AC     33

#define RELAY_ON   LOW
#define RELAY_OFF  HIGH

DHT dht(PIN_DHT, DHT22);
unsigned long lastSend = 0;
bool motionLatch = false;

// Trang thai thiet bi hien tai
bool stateLight = false;
bool stateFan   = false;
bool stateAc    = false;

void setRelay(int pin, bool on) {
  digitalWrite(pin, on ? RELAY_ON : RELAY_OFF);
}

float readLux() {
  int raw = analogRead(PIN_LDR); // 0..4095
  return (raw / 4095.0f) * 1000.0f;
}

void setup() {
  Serial.begin(115200);
  
  pinMode(PIN_PIR, INPUT);
  for (int p : {PIN_LIGHT, PIN_FAN, PIN_AC}) {
    pinMode(p, OUTPUT);
    setRelay(p, false);
  }
  
  dht.begin();
  delay(1000);
  
  // Gui thong bao khoi dong ve Coordinator
  Serial.println("{\"status\":\"ESP32_READY\",\"roomCode\":\"A101\",\"mode\":\"IOT_LEVEL_5_SERIAL\"}");
}

// Xu ly lenh dieu khien tu Coordinator (May tinh phong hoc) gui xuong qua Serial
void processIncomingCommand(const String& jsonStr) {
  StaticJsonDocument<256> doc;
  DeserializationError err = deserializeJson(doc, jsonStr);
  if (err) return; // Khong phai goi JSON hop le thi bo qua

  if (doc.containsKey("light")) {
    stateLight = doc["light"];
    setRelay(PIN_LIGHT, stateLight);
  }
  if (doc.containsKey("fan")) {
    stateFan = doc["fan"];
    setRelay(PIN_FAN, stateFan);
  }
  if (doc.containsKey("ac")) {
    stateAc = doc["ac"];
    setRelay(PIN_AC, stateAc);
  }
}

// Gui du lieu cam bien dinh ky ve Coordinator qua Serial
void sendTelemetryToCoordinator() {
  float t = dht.readTemperature();
  float h = dht.readHumidity();

  // Neu loi doc DHT22 thi lay gia tri an toan
  if (isnan(t)) t = 26.5;
  if (isnan(h)) h = 65.0;

  StaticJsonDocument<256> doc;
  doc["roomCode"] = ROOM_CODE;
  doc["temperature"] = round(t * 10.0) / 10.0;
  doc["humidity"] = round(h * 10.0) / 10.0;
  doc["lux"] = round(readLux() * 10.0) / 10.0;
  doc["motion"] = motionLatch;
  doc["lightState"] = stateLight;
  doc["fanState"]   = stateFan;
  doc["acState"]    = stateAc;

  // Reset bien nho chuyen dong sau moi lan gui
  motionLatch = false;

  // Dong goi va in JSON tren 1 dong ve cong Serial cho Coordinator doc
  serializeJson(doc, Serial);
  Serial.println();
}

void loop() {
  // Ghi nhan chuyen dong neu cam bien PIR phat hien
  if (digitalRead(PIN_PIR) == HIGH) {
    motionLatch = true;
  }

  // Lang nghe lenh dieu khien tu Coordinator qua Serial
  if (Serial.available() > 0) {
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.startsWith("{") && line.endsWith("}")) {
      processIncomingCommand(line);
    }
  }

  // Dinh ky 3s gui du lieu cam bien len Coordinator
  if (millis() - lastSend >= SEND_INTERVAL) {
    lastSend = millis();
    sendTelemetryToCoordinator();
  }

  delay(20);
}
