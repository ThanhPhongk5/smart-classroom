#include <Wire.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <DHT.h>

// ---- 1. CAU HINH WIFI (Thay bang ten va mat khau WiFi nha / phong cua ban) ----
const char* ssid     = "TEN_WIFI";
const char* password = "MAT_KHAU_WIFI";

// ---- 2. CAU HINH SERVER WEB DASHBOARD ----
// Server Cloud Render dang hoat dong cua he thong:
const char* serverUrl = "https://smart-classroom-pyvl.onrender.com/api/device/telemetry";
const char* deviceKey = "esp32-secret-2026";
const char* roomCode  = "A101";

// ---- 3. CAU HINH CHAN PHAN CUNG ----
#define DHT_PIN    4
#define DHT_TYPE   DHT11
#define LDR_PIN    34

// ---- 4. OLED SSD1306 (128x64 I2C: SDA=21, SCL=22) ----
#define SCREEN_WIDTH  128
#define SCREEN_HEIGHT 64
#define OLED_ADDR     0x3C

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);
DHT dht(DHT_PIN, DHT_TYPE);

int batteryLevel = 85;
unsigned long lastSendTime = 0;
unsigned long lastSerialTime = 0;
String webStatus = "Cho dong bo...";

// Ve bieu tuong song WiFi: 4 vach theo cuong do tin hieu
void drawWifi(int x, int y) {
  int bars = 0;
  if (WiFi.status() == WL_CONNECTED) {
    int rssi = WiFi.RSSI();
    if (rssi > -55)      bars = 4;
    else if (rssi > -65) bars = 3;
    else if (rssi > -75) bars = 2;
    else                 bars = 1;
  }
  for (int i = 0; i < 4; i++) {
    int h = 2 + i * 2;
    if (i < bars) display.fillRect(x + i * 4, y + 8 - h, 3, h, SSD1306_WHITE);
    else          display.drawRect(x + i * 4, y + 8 - h, 3, h, SSD1306_WHITE);
  }
}

// Ve bieu tuong pin
void drawBattery(int x, int y, int level) {
  display.drawRect(x, y, 16, 8, SSD1306_WHITE);
  display.fillRect(x + 16, y + 2, 2, 4, SSD1306_WHITE);
  display.fillRect(x + 2, y + 2, map(level, 0, 100, 0, 12), 4, SSD1306_WHITE);
}

// Gui du lieu nhiet do, anh sang truc tiep len Web Dashboard qua WiFi
void sendDataToWeb(float temp, float hum, float lux) {
  if (WiFi.status() == WL_CONNECTED) {
    WiFiClientSecure client;
    client.setInsecure(); // Bo qua kiem tra SSL cert cua Render de ket noi HTTPS thanh cong 100%

    HTTPClient http;
    http.begin(client, serverUrl);
    http.setTimeout(4000);
    http.addHeader("Content-Type", "application/json");
    http.addHeader("X-Device-Key", deviceKey);

    // Dong goi goi tin JSON theo dung chuan Web can
    String jsonPayload = "{\"roomCode\":\"" + String(roomCode) + "\","
                         "\"temperature\":" + String(temp, 1) + ","
                         "\"humidity\":" + String(hum, 0) + ","
                         "\"lux\":" + String(lux, 1) + ","
                         "\"motion\":false}";

    int httpCode = http.POST(jsonPayload);
    if (httpCode == 200) {
      webStatus = "Web: OK";
      Serial.println("[WEB SYNC] Da gui thanh cong len Web Dashboard 200 OK!");
    } else {
      webStatus = "Web:" + String(httpCode);
      Serial.printf("[WEB SYNC] Loi ket noi Web: %d\n", httpCode);
    }
    http.end();
  } else {
    webStatus = "No WiFi";
  }
}

void setup() {
  Serial.begin(115200);

  Wire.begin(21, 22); // SDA=GPIO 21, SCL=GPIO 22

  if (!display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR)) {
    Serial.println("Khong tim thay OLED! Kiem tra day SDA/SCL.");
    while (true) delay(10);
  }

  dht.begin();
  analogReadResolution(12); // 0 - 4095

  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1);
  display.setCursor(0, 0);
  display.println("ESP32 SMARTCLASS");
  display.setCursor(0, 16);
  display.println("Dang ket noi WiFi...");
  display.display();
  delay(1500);
}

void loop() {
  float temp = dht.readTemperature();
  float hum  = dht.readHumidity();
  int ldrRaw = analogRead(LDR_PIN);

  // Chuyen doi ADC sang phan tram va uoc tinh Lux (0..1000 lux)
  int lightPercent = map(ldrRaw, 0, 4095, 100, 0);
  float luxApprox = (float)lightPercent * 10.0;

  display.clearDisplay();
  display.setTextSize(1);

  // Dong tieu de + bieu tuong Wifi, Pin
  display.setCursor(0, 0);
  display.print("A101");
  display.setCursor(32, 0);
  display.print(WiFi.status() == WL_CONNECTED ? "ONLINE" : "NO WIFI");
  drawWifi(88, 0);
  drawBattery(108, 0, batteryLevel);

  // Nhiet do, do am
  if (isnan(temp) || isnan(hum)) {
    display.setCursor(0, 16);
    display.print("DHT11: loi doc!");
  } else {
    display.setCursor(0, 16);
    display.printf("Nhiet do: %.1f C", temp);

    display.setCursor(0, 28);
    display.printf("Do am   : %.0f %%", hum);
  }

  // Anh sang
  display.setCursor(0, 40);
  display.printf("Anh sang: %d%% (%dlx)", lightPercent, (int)luxApprox);

  // Thanh hien thi muc sang nua trai
  display.drawRect(0, 52, 60, 11, SSD1306_WHITE);
  display.fillRect(2, 54, map(lightPercent, 0, 100, 0, 56), 7, SSD1306_WHITE);

  // Hien thi trang thai dong bo Web o nua phai
  display.setCursor(68, 54);
  display.print(webStatus);

  display.display();

  // In ra Serial dinh dang JSON moi 1 giay (tranh lam tran bo dem UART tren PC gay tre du lieu)
  if (millis() - lastSerialTime >= 1000) {
    lastSerialTime = millis();
    Serial.printf("{\"roomCode\":\"%s\",\"temperature\":%.1f,\"humidity\":%.0f,\"lux\":%.1f,\"motion\":false}\n",
                  roomCode,
                  isnan(temp) ? 26.5 : temp,
                  isnan(hum) ? 60.0 : hum,
                  luxApprox);
  }

  // Dinh ky moi 3 giay gui du lieu len Web Dashboard
  if (millis() - lastSendTime >= 3000) {
    lastSendTime = millis();
    if (!isnan(temp) && !isnan(hum)) {
      sendDataToWeb(temp, hum, luxApprox);
    }
  }

  delay(100);
}
