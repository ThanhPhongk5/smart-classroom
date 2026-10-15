/*
 * Lop hoc thong minh - ESP32
 * Thu vien can cai (Library Manager): "DHT sensor library" (Adafruit), "Adafruit Unified Sensor", "ArduinoJson" (v7)
 * Cam bien: DHT22 (nhiet do/do am), LDR + dien tro 10k (anh sang), PIR HC-SR501 (co nguoi)
 * Co cau chap hanh: module relay 3 kenh (den, quat, dieu hoa) - relay kich muc THAP (active-low)
 */
#include <WiFi.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <DHT.h>

// ====== CAU HINH ======
const char* WIFI_SSID  = "TEN_WIFI";
const char* WIFI_PASS  = "MAT_KHAU_WIFI";
const char* SERVER_URL = "http://192.168.1.10:8080/api/device/telemetry";  // IP may chay Spring Boot
const char* DEVICE_KEY = "esp32-secret-2026";   // trung voi app.device-key
const char* ROOM_CODE  = "A101";
const unsigned long SEND_INTERVAL = 5000;       // ms

#define PIN_DHT    4
#define PIN_LDR    34     // chi doc analog (ADC1)
#define PIN_PIR    27
#define PIN_LIGHT  25
#define PIN_FAN    26
#define PIN_AC     33
#define RELAY_ON   LOW
#define RELAY_OFF  HIGH

DHT dht(PIN_DHT, DHT22);
unsigned long lastSend = 0;
bool motionLatch = false;   // nho chuyen dong xay ra giua 2 lan gui

void setRelay(int pin, bool on) { digitalWrite(pin, on ? RELAY_ON : RELAY_OFF); }

void connectWifi() {
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("Ket noi WiFi");
  while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
  Serial.println("\nIP: " + WiFi.localIP().toString());
}

// LDR -> lux gan dung (can hieu chinh voi lux ke that neu muon chinh xac)
float readLux() {
  int raw = analogRead(PIN_LDR);               // 0..4095, sang -> gia tri cao khi LDR noi ve GND
  return (raw / 4095.0f) * 1000.0f;
}

void sendTelemetry() {
  float t = dht.readTemperature(), h = dht.readHumidity();
  if (isnan(t) || isnan(h)) { Serial.println("Loi doc DHT22"); return; }

  JsonDocument doc;
  doc["roomCode"] = ROOM_CODE;
  doc["temperature"] = t;
  doc["humidity"] = h;
  doc["lux"] = readLux();
  doc["motion"] = motionLatch;
  motionLatch = false;
  String body; serializeJson(doc, body);

  HTTPClient http;
  http.begin(SERVER_URL);
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Device-Key", DEVICE_KEY);
  int code = http.POST(body);
  if (code == 200) {
    JsonDocument cmd;
    if (!deserializeJson(cmd, http.getString())) {       // phan hoi = lenh dieu khien
      setRelay(PIN_LIGHT, cmd["light"]);
      setRelay(PIN_FAN,   cmd["fan"]);
      setRelay(PIN_AC,    cmd["ac"]);
    }
  } else {
    Serial.printf("POST that bai, ma = %d\n", code);
  }
  http.end();
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_PIR, INPUT);
  for (int p : {PIN_LIGHT, PIN_FAN, PIN_AC}) { pinMode(p, OUTPUT); setRelay(p, false); }
  dht.begin();
  connectWifi();
}

void loop() {
  if (digitalRead(PIN_PIR) == HIGH) motionLatch = true;
  if (WiFi.status() != WL_CONNECTED) connectWifi();
  if (millis() - lastSend >= SEND_INTERVAL) { lastSend = millis(); sendTelemetry(); }
  delay(50);
}
