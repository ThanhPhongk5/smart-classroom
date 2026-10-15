"""
Hệ thống Giám sát Hành vi Học sinh bằng YOLO11 (Smart Classroom AI Monitor)
Đồng bộ thời gian thực với Spring Boot Backend
"""

import sys
import os
import time
import argparse
import threading
import queue
import requests
import cv2
import numpy as np

try:
    from PIL import Image, ImageDraw, ImageFont
    PIL_AVAILABLE = True
except ImportError:
    PIL_AVAILABLE = False

try:
    from ultralytics import YOLO
except ImportError:
    print("[ERROR] Thư viện 'ultralytics' chưa được cài đặt!")
    print("Vui lòng chạy lệnh: pip install ultralytics opencv-python requests pillow")
    sys.exit(1)

# Định nghĩa 6 nhãn hành vi theo mô hình
BEHAVIOR_CLASSES = {
    0: {"name_en": "hand-raising", "name_vi": "Gio tay", "color": (46, 204, 113), "type": "positive"},
    1: {"name_en": "reading", "name_vi": "Doc sach", "color": (52, 152, 219), "type": "positive"},
    2: {"name_en": "writing", "name_vi": "Viet bai", "color": (155, 89, 182), "type": "positive"},
    3: {"name_en": "using phone", "name_vi": "Dung dien thoai", "color": (40, 40, 235), "type": "violation"},
    4: {"name_en": "bowing the head", "name_vi": "Guc dau", "color": (34, 126, 230), "type": "warning"},
    5: {"name_en": "leaning over the table", "name_vi": "Nam ra ban", "color": (15, 196, 241), "type": "warning"}
}


class TelemetrySender(threading.Thread):
    """Thread gửi dữ liệu thống kê lên Backend không làm đứng hình camera"""
    def __init__(self, server_url, device_key, room_code, interval=2.0):
        super().__init__(daemon=True)
        self.server_url = server_url.rstrip("/") + "/api/device/behavior"
        self.device_key = device_key
        self.room_code = room_code
        self.interval = interval
        self.queue = queue.Queue(maxsize=1)
        self.running = True

    def send(self, stats):
        try:
            # Chỉ giữ frame thống kê mới nhất
            if not self.queue.full():
                self.queue.put_nowait(stats)
        except queue.Full:
            pass

    def run(self):
        last_sent = 0
        while self.running:
            try:
                stats = self.queue.get(timeout=0.5)
                now = time.time()
                if now - last_sent >= self.interval:
                    payload = {
                        "roomCode": self.room_code,
                        "totalStudents": stats.get("total", 0),
                        "handRaising": stats.get(0, 0),
                        "reading": stats.get(1, 0),
                        "writing": stats.get(2, 0),
                        "usingPhone": stats.get(3, 0),
                        "bowingHead": stats.get(4, 0),
                        "leaningTable": stats.get(5, 0)
                    }
                    headers = {
                        "Content-Type": "application/json",
                        "X-Device-Key": self.device_key
                    }
                    resp = requests.post(self.server_url, json=payload, headers=headers, timeout=2.0)
                    if resp.status_code == 200:
                        data = resp.json()
                        # print(f"[SYNC] GUI THANH CONG: Si so={data.get('totalStudents')} | Tap trung={data.get('attentionScore')}%")
                    last_sent = now
            except queue.Empty:
                continue
            except Exception as e:
                # print(f"[SYNC WARN] Khong the ket noi backend: {e}")
                time.sleep(1)


def draw_hud(frame, stats, fps, attention_score, room_code):
    """Vẽ giao diện hiển thị thông số HUD hiện đại trên luồng video"""
    h, w, _ = frame.shape

    # Panel bán trong suốt góc trên bên trái
    overlay = frame.copy()
    cv2.rectangle(overlay, (10, 10), (330, 240), (20, 28, 38), -1)
    cv2.addWeighted(overlay, 0.75, frame, 0.25, 0, frame)
    cv2.rectangle(frame, (10, 10), (330, 240), (60, 85, 110), 1)

    # Tiêu đề
    cv2.putText(frame, f"SMART CLASSROOM - {room_code}", (20, 35),
                cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 2)
    cv2.putText(frame, f"FPS: {int(fps)}", (260, 35),
                cv2.FONT_HERSHEY_SIMPLEX, 0.45, (160, 180, 200), 1)

    # Chỉ số tập trung
    score_color = (46, 204, 113) if attention_score >= 70 else (34, 126, 230) if attention_score >= 45 else (40, 40, 235)
    cv2.putText(frame, f"Tap trung: {attention_score:.1f}%", (20, 68),
                cv2.FONT_HERSHEY_SIMPLEX, 0.75, score_color, 2)
    cv2.putText(frame, f"Si so phat hien: {stats.get('total', 0)} hoc sinh", (20, 95),
                cv2.FONT_HERSHEY_SIMPLEX, 0.5, (220, 220, 220), 1)

    # Đường phân cách
    cv2.line(frame, (20, 108), (320, 108), (80, 100, 120), 1)

    # Chi tiết từng hành vi
    rows = [
        ("0. Gio tay (hand-raising)", stats.get(0, 0), (46, 204, 113)),
        ("1. Doc sach (reading)", stats.get(1, 0), (52, 152, 219)),
        ("2. Viet bai (writing)", stats.get(2, 0), (155, 89, 182)),
        ("3. Dung DT (phone)", stats.get(3, 0), (40, 40, 235)),
        ("4. Guc dau (bowing)", stats.get(4, 0), (34, 126, 230)),
        ("5. Nam bo ban (leaning)", stats.get(5, 0), (15, 196, 241)),
    ]

    y = 128
    for label, count, col in rows:
        cv2.circle(frame, (26, y - 4), 4, col, -1)
        cv2.putText(frame, f"{label}:", (36, y), cv2.FONT_HERSHEY_SIMPLEX, 0.42, (200, 210, 220), 1)
        cv2.putText(frame, str(count), (290, y), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 2)
        y += 18

    # Banner cảnh báo nếu phát hiện dùng điện thoại
    phone_count = stats.get(3, 0)
    if phone_count > 0:
        cv2.rectangle(frame, (w - 380, 15), (w - 15, 60), (30, 30, 200), -1)
        cv2.rectangle(frame, (w - 380, 15), (w - 15, 60), (100, 100, 255), 2)
        cv2.putText(frame, f"[CANH BAO] PHAT HIEN DUNG DIEN THOAI! ({phone_count})",
                    (w - 365, 43), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 2)


def main():
    parser = argparse.ArgumentParser(description="Classroom Behavior AI Monitor")
    parser.add_argument("--model", default="bestYolo11n.pt", help="Đường dẫn file mô hình .pt")
    parser.add_argument("--source", default="0", help="Webcam id (0, 1) hoặc đường dẫn file video / luồng RTSP")
    parser.add_argument("--room", default="A101", help="Mã phòng học (A101, A102)")
    parser.add_argument("--server", default="http://localhost:8080", help="URL máy chủ Spring Boot")
    parser.add_argument("--key", default="esp32-secret-2026", help="Khóa xác thực thiết bị")
    parser.add_argument("--conf", type=float, default=0.35, help="Ngưỡng tin cậy confidence (mặc định 0.35)")
    args = parser.parse_args()

    # Tìm file model nếu ở thư mục gốc
    model_path = args.model
    if not os.path.exists(model_path):
        script_dir = os.path.dirname(os.path.abspath(__file__))
        cand = os.path.join(script_dir, args.model)
        if os.path.exists(cand):
            model_path = cand
        elif os.path.exists(r"C:\Users\ADMIN\Downloads\bestYolo11n.pt"):
            model_path = r"C:\Users\ADMIN\Downloads\bestYolo11n.pt"

    print("=" * 60)
    print("  SMART CLASSROOM - STUDENT BEHAVIOR MONITOR (YOLO11)")
    print(f"  * Model:   {model_path}")
    print(f"  * Source:  {args.source}")
    print(f"  * Room:    {args.room}")
    print(f"  * Server:  {args.server}")
    print("=" * 60)

    print("[INFO] Đang tải mô hình YOLO11...")
    model = YOLO(model_path)
    print("[INFO] Tải mô hình thành công!")

    # Khởi tạo nguồn video
    source = int(args.source) if args.source.isdigit() else args.source
    cap = cv2.VideoCapture(source)
    if not cap.isOpened():
        print(f"[ERROR] Không thể mở nguồn camera/video: {source}")
        return

    # Khởi động thread gửi dữ liệu
    sender = TelemetrySender(args.server, args.key, args.room, interval=2.0)
    sender.start()
    print(f"[INFO] Bắt đầu giám sát và đồng bộ về phòng {args.room}...")
    print("[TIP] Nhấn phím 'q' trên cửa sổ video để dừng, 's' để chụp ảnh.")

    prev_time = time.time()
    frame_count = 0

    while True:
        ret, frame = cap.read()
        if not ret:
            # Nếu là file video thì lặp lại từ đầu
            if isinstance(source, str) and os.path.exists(source):
                cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                continue
            else:
                print("[WARN] Mất tín hiệu camera...")
                break

        frame_count += 1
        curr_time = time.time()
        fps = 1.0 / max(0.001, curr_time - prev_time)
        prev_time = curr_time

        # Chạy inference mô hình
        results = model.predict(frame, conf=args.conf, verbose=False)

        stats = {0: 0, 1: 0, 2: 0, 3: 0, 4: 0, 5: 0, "total": 0}

        if len(results) > 0 and results[0].boxes is not None:
            boxes = results[0].boxes
            for box in boxes:
                cls_id = int(box.cls[0].item())
                conf = float(box.conf[0].item())
                xyxy = box.xyxy[0].cpu().numpy().astype(int)

                if cls_id in BEHAVIOR_CLASSES:
                    stats[cls_id] = stats.get(cls_id, 0) + 1
                    stats["total"] += 1

                    meta = BEHAVIOR_CLASSES[cls_id]
                    color = meta["color"]
                    label_text = f"{meta['name_vi']} {conf:.2f}"

                    # Vẽ bounding box
                    cv2.rectangle(frame, (xyxy[0], xyxy[1]), (xyxy[2], xyxy[3]), color, 2)

                    # Nhãn phía trên box
                    (lw, lh), _ = cv2.getTextSize(label_text, cv2.FONT_HERSHEY_SIMPLEX, 0.45, 1)
                    cv2.rectangle(frame, (xyxy[0], xyxy[1] - 20), (xyxy[0] + lw + 8, xyxy[1]), color, -1)
                    cv2.putText(frame, label_text, (xyxy[0] + 4, xyxy[1] - 5),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1)

        # Tính điểm tập trung (%)
        total = stats["total"]
        if total > 0:
            positive = stats[0] + stats[1] + stats[2]
            attention_score = min(100.0, max(0.0, (positive * 100.0) / total))
        else:
            attention_score = 0.0

        # Gửi thống kê sang background sender thread
        sender.send(stats)

        # Vẽ giao diện HUD
        draw_hud(frame, stats, fps, attention_score, args.room)

        # Hiển thị lên màn hình
        cv2.imshow("Smart Classroom - Student Behavior AI Monitor", frame)

        key = cv2.waitKey(1) & 0xFF
        if key == ord('q') or key == 27:
            print("[INFO] Dừng chương trình theo yêu cầu người dùng.")
            break
        elif key == ord('s'):
            snap_name = f"snapshot_{int(time.time())}.jpg"
            cv2.imwrite(snap_name, frame)
            print(f"[INFO] Đã lưu ảnh chụp: {snap_name}")

    cap.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()