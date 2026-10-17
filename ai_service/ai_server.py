"""
Smart Classroom AI Server - Realtime High-Sensitivity Detection Engine
Ket hop YOLO11 Object Detection (COCO Phone conf=0.18) + bestYolo11n.pt + Realtime Buffer Drain
=> Phat hien dien thoai va gio tay TUC THI tren chinh khung hinh hien tai (khong do tre).
"""

import os
import sys
import time
import random
import threading
import requests
import cv2
import numpy as np
import torch
from flask import Flask, Response, request, jsonify
from flask_cors import CORS
from werkzeug.utils import secure_filename
from ultralytics import YOLO

# Toi uu luong CPU cho PyTorch
torch.set_num_threads(min(6, os.cpu_count() or 6))

app = Flask(__name__)
CORS(app)

@app.after_request
def add_cors_headers(response):
    response.headers['Access-Control-Allow-Origin'] = '*'
    response.headers['Access-Control-Allow-Methods'] = 'GET, POST, OPTIONS, PUT, DELETE'
    response.headers['Access-Control-Allow-Headers'] = 'Content-Type, X-Device-Key, Authorization'
    response.headers['Access-Control-Allow-Private-Network'] = 'true'
    return response

UPLOAD_FOLDER = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'uploads')
os.makedirs(UPLOAD_FOLDER, exist_ok=True)
app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER
app.config['MAX_CONTENT_LENGTH'] = 500 * 1024 * 1024

script_dir = os.path.dirname(os.path.abspath(__file__))
obj_path = os.path.join(script_dir, 'yolo11n.pt')
behavior_path = os.path.join(script_dir, 'bestYolo11n.pt')
pose_path = os.path.join(script_dir, 'yolo11n-pose.pt')

print('[INFO] Dang khoi dong cac mo hinh AI Smart Classroom...')
obj_model = YOLO(obj_path) if os.path.exists(obj_path) else None
behavior_model = YOLO(behavior_path) if os.path.exists(behavior_path) else None
pose_model = YOLO(pose_path) if os.path.exists(pose_path) else None

# Warmup models
dummy = np.zeros((480, 640, 3), dtype=np.uint8)
if obj_model: obj_model.predict(dummy, classes=[67, 73], conf=0.18, imgsz=416, verbose=False)
if behavior_model: behavior_model.predict(dummy, conf=0.20, imgsz=416, verbose=False)
if pose_model: pose_model.predict(dummy, conf=0.25, imgsz=416, verbose=False)
print('[INFO] Tat ca mo hinh AI da san sang!')

BACKEND_BASE_URL = os.environ.get('BACKEND_URL', 'https://smart-classroom-pyvl.onrender.com')
SPRING_BOOT_URL = f'{BACKEND_BASE_URL}/api/device/behavior'
SPRING_BOOT_SEATS_URL = f'{BACKEND_BASE_URL}/api/device/seats'
DEVICE_KEY = 'esp32-secret-2026'

BEHAVIOR_CLASSES = {
    0: {"name_vi": "Gio tay", "color": (46, 204, 113), "status": "raising_hand"},
    1: {"name_vi": "Doc sach", "color": (52, 152, 219), "status": "studying"},
    2: {"name_vi": "Viet bai", "color": (155, 89, 182), "status": "studying"},
    3: {"name_vi": "Dung dien thoai", "color": (40, 40, 235), "status": "using_phone"},
    4: {"name_vi": "Guc dau", "color": (34, 126, 230), "status": "bowing_head"},
    5: {"name_vi": "Nam bo ban", "color": (15, 196, 241), "status": "bowing_head"}
}

SKELETON_EDGES = [
    (0, 1), (0, 2), (1, 3), (2, 4),
    (5, 6), (5, 7), (7, 9),
    (6, 8), (8, 10),
    (5, 11), (6, 12), (11, 12)
]

def map_coord_to_seat(norm_x, norm_y, total_rows=5, total_cols=4, seats_per_desk=2):
    col = min(total_cols, max(1, int(norm_x * total_cols) + 1))
    row = min(total_rows, max(1, int(norm_y * total_rows) + 1))
    if seats_per_desk == 1:
        return f'B{row}-D{col}'
    cell_width = 1.0 / total_cols
    local_x = (norm_x - (col - 1) * cell_width) / cell_width
    side = 'L' if local_x < 0.5 else 'R'
    return f'B{row}-D{col}{side}'


class RealtimeCapture:
    """
    Luong doc webcam chuyen dung: luon hut frame moi nhat tu phan cung,
    xoa sach hang doi buffer cua Windows DirectShow de triet tieu hoan toan do tre.
    """
    def __init__(self, cam_id=0):
        self.cap = None
        if sys.platform.startswith('win'):
            self.cap = cv2.VideoCapture(cam_id, cv2.CAP_DSHOW)
        if self.cap is None or not self.cap.isOpened():
            self.cap = cv2.VideoCapture(cam_id)
        if self.cap and self.cap.isOpened():
            self.cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*'MJPG'))
            self.cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
            self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
            self.cap.set(cv2.CAP_PROP_FPS, 30)
            self.cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

        self.ret = False
        self.frame = None
        self.running = True
        self.lock = threading.Lock()
        self.new_frame_event = threading.Event()
        self.thread = threading.Thread(target=self._grab_loop, daemon=True)
        self.thread.start()

    def _grab_loop(self):
        while self.running:
            if not self.cap or not self.cap.isOpened():
                time.sleep(0.05)
                continue
            ret, frame = self.cap.read()
            if ret:
                with self.lock:
                    self.ret = True
                    self.frame = frame
                self.new_frame_event.set()
            else:
                time.sleep(0.005)

    def read_latest(self, timeout=0.08):
        if self.new_frame_event.wait(timeout):
            self.new_frame_event.clear()
            with self.lock:
                if not self.ret or self.frame is None:
                    return False, None
                return True, self.frame.copy()
        with self.lock:
            if not self.ret or self.frame is None:
                return False, None
            return True, self.frame.copy()

    def isOpened(self):
        return self.cap is not None and self.cap.isOpened()

    def release(self):
        self.running = False
        self.new_frame_event.set()
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=0.4)
        if self.cap:
            try:
                self.cap.release()
            except Exception:
                pass
            self.cap = None


class VideoProcessor:
    def __init__(self):
        self.cap = None
        self.is_running = False
        self.mode = 'idle'
        self.room_code = 'A101'
        self.current_frame = None
        self.lock = threading.Lock()
        self.thread = None
        self.fps = 0.0
        self.total_students = 0
        self.attention_score = 0.0
        self.stats = {0: 0, 1: 0, 2: 0, 3: 0, 4: 0, 5: 0}
        self.last_sync_time = 0
        self.frame_id = 0
        self.phone_detected_alert = False

        # Cau hinh phong hoc
        self.total_rows = 5
        self.total_cols = 4
        self.seats_per_desk = 2

        # Che do Demo 1 nguoi / phong hoc that
        self.demo_mode = True
        self.demo_seat_code = 'B1-D1L'

    def fetch_room_layout(self):
        try:
            res = requests.get(f'{BACKEND_BASE_URL}/api/rooms', timeout=2.5)
            if res.status_code == 200:
                for r in res.json():
                    if r.get('code') == self.room_code:
                        self.total_rows = r.get('rowCount', 5)
                        self.total_cols = r.get('colCount', 4)
                        self.seats_per_desk = r.get('seatsPerDesk', 2)
                        break
        except Exception:
            pass

    def start_webcam(self, cam_id=0, room_code='A101', demo_mode=True, demo_seat=None):
        self.stop()
        self.room_code = room_code
        self.demo_mode = demo_mode
        if demo_seat:
            self.demo_seat_code = demo_seat
        self.fetch_room_layout()
        self.mode = 'webcam'
        self.cap = RealtimeCapture(cam_id)
        if not self.cap or not self.cap.isOpened():
            self.mode = 'idle'
            return False, 'Khong the mo Webcam'

        self.is_running = True
        self.thread = threading.Thread(target=self._process_loop, daemon=True)
        self.thread.start()
        return True, 'Mo Webcam thanh cong (Che do Demo: ' + ('Bat' if self.demo_mode else 'Tat') + ')'

    def start_video(self, video_path, room_code='A101'):
        self.stop()
        self.room_code = room_code
        self.demo_mode = False
        self.fetch_room_layout()
        self.mode = 'video'
        self.cap = cv2.VideoCapture(video_path)
        if not self.cap.isOpened():
            self.mode = 'idle'
            return False, 'Khong the doc video'
        self.is_running = True
        self.thread = threading.Thread(target=self._process_loop, args=(video_path,), daemon=True)
        self.thread.start()
        return True, 'Bat dau phan tich video'

    def stop(self):
        self.is_running = False
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=0.6)
        if self.cap:
            try:
                self.cap.release()
            except Exception:
                pass
            self.cap = None
        self.mode = 'idle'
        self.current_frame = None

    def _sync_to_backend(self, seat_updates, overall_stats, student_count, attention_score):
        now = time.time()
        if now - self.last_sync_time < 0.6:
            return
        self.last_sync_time = now

        payload_behavior = {
            'roomCode': self.room_code,
            'totalStudents': student_count,
            'handRaising': overall_stats.get(0, 0),
            'reading': overall_stats.get(1, 0),
            'writing': overall_stats.get(2, 0),
            'usingPhone': overall_stats.get(3, 0),
            'bowingHead': overall_stats.get(4, 0),
            'leaningTable': overall_stats.get(5, 0)
        }

        def _post():
            try:
                requests.post(SPRING_BOOT_URL, json=payload_behavior,
                              headers={'X-Device-Key': DEVICE_KEY, 'Content-Type': 'application/json'},
                              timeout=1.5)
                if seat_updates:
                    payload_seats = {
                        'roomCode': self.room_code,
                        'updates': seat_updates
                    }
                    requests.post(SPRING_BOOT_SEATS_URL, json=payload_seats,
                                  headers={'X-Device-Key': DEVICE_KEY, 'Content-Type': 'application/json'},
                                  timeout=1.5)
            except Exception:
                pass

        threading.Thread(target=_post, daemon=True).start()


    def _process_loop(self, loop_path=None):
        """Xu ly in-line truc tiep tren tung frame: nhan dien tuc thi, dong nhat tuyet doi voi cu dong"""
        prev_time = time.time()

        while self.is_running and self.cap:
            if hasattr(self.cap, 'read_latest'):
                ret, frame = self.cap.read_latest()
            else:
                ret, frame = self.cap.read()

            if not ret or frame is None:
                if loop_path and os.path.exists(loop_path):
                    self.cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                    continue
                else:
                    time.sleep(0.005)
                    continue

            h, w, _ = frame.shape
            curr_time = time.time()
            self.fps = 1.0 / max(0.001, curr_time - prev_time)
            prev_time = curr_time

            stats = {0: 0, 1: 0, 2: 0, 3: 0, 4: 0, 5: 0}
            total = 0
            seat_updates = []
            demo_user_detected = False
            demo_user_action = 'Hoc bai'
            demo_user_status = 'studying'
            has_phone = False
            detected_phones = []

            # 1. Phat hien dien thoai qua YOLO11 Object Detection (COCO cell phone 67, conf=0.18 sieu nhay)
            if obj_model is not None:
                obj_results = obj_model.predict(frame, classes=[67, 73], conf=0.18, imgsz=416, verbose=False)
                if len(obj_results) > 0 and obj_results[0].boxes is not None:
                    for b in obj_results[0].boxes:
                        cid = int(b.cls[0].item())
                        box = b.xyxy[0].cpu().numpy().astype(int)
                        conf = float(b.conf[0].item())
                        if cid == 67:
                            detected_phones.append((box, conf))
                            has_phone = True

            # 2. Phat hien cac hanh vi lop hoc qua bestYolo11n.pt
            behav_boxes = []
            if behavior_model is not None:
                b_results = behavior_model.predict(frame, conf=0.20, imgsz=416, verbose=False)
                if len(b_results) > 0 and b_results[0].boxes is not None:
                    for b in b_results[0].boxes:
                        cid = int(b.cls[0].item())
                        box = b.xyxy[0].cpu().numpy().astype(int)
                        conf = float(b.conf[0].item())
                        behav_boxes.append((cid, box, conf))
                        if cid == 3: # Using phone
                            has_phone = True

            # 3. Xu ly hanh vi hoc sinh tu mo hinh chuyen dung
            for (cid, box, conf) in behav_boxes:
                if cid in BEHAVIOR_CLASSES:
                    total += 1
                    px1, py1, px2, py2 = box
                    cx = (px1 + px2) / 2.0
                    cy = (py1 + py2) / 2.0
                    norm_x = cx / max(1, w)
                    norm_y = cy / max(1, h)

                    act_id = 3 if has_phone else cid
                    meta = BEHAVIOR_CLASSES[act_id]
                    action_name = meta['name_vi']
                    status_key = meta['status']
                    box_color = meta['color']

                    stats[act_id] = stats.get(act_id, 0) + 1

                    if self.demo_mode and total == 1:
                        target_seat = self.demo_seat_code
                        demo_user_detected = True
                        demo_user_action = action_name
                        demo_user_status = status_key
                        seat_label = f'[DEMO] {target_seat}: {action_name}'
                    else:
                        target_seat = map_coord_to_seat(norm_x, norm_y, self.total_rows, self.total_cols, self.seats_per_desk)
                        seat_label = f'{target_seat}: {action_name}'

                    seat_updates.append({
                        'seatCode': target_seat,
                        'status': status_key,
                        'action': action_name
                    })

                    # Ve bounding box len chinh frame nay
                    cv2.rectangle(frame, (px1, py1), (px2, py2), box_color, 2)
                    (lw, lh), _ = cv2.getTextSize(seat_label, cv2.FONT_HERSHEY_SIMPLEX, 0.50, 2)
                    cv2.rectangle(frame, (px1, max(0, py1 - 24)), (px1 + lw + 12, py1), box_color, -1)
                    cv2.putText(frame, seat_label, (px1 + 4, max(16, py1 - 7)),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.50, (255, 255, 255), 2)

            # 4. Neu bestYolo11n chua bat duoc nguoi (ngoi gan webcam), su dung pose_model
            if total == 0 and pose_model is not None:
                p_results = pose_model.predict(frame, conf=0.25, imgsz=416, verbose=False)
                if len(p_results) > 0 and p_results[0].boxes is not None and p_results[0].keypoints is not None:
                    p_boxes = p_results[0].boxes.xyxy.cpu().numpy().astype(int)
                    kpts_data = p_results[0].keypoints.data.cpu().numpy()

                    for i, p_box in enumerate(p_boxes):
                        total += 1
                        kpts = kpts_data[i]
                        px1, py1, px2, py2 = p_box
                        cx = (px1 + px2) / 2.0
                        cy = (py1 + py2) / 2.0
                        norm_x = cx / max(1, w)
                        norm_y = cy / max(1, h)

                        l_shoulder, r_shoulder = kpts[5], kpts[6]
                        l_elbow, r_elbow = kpts[7], kpts[8]
                        l_wrist, r_wrist = kpts[9], kpts[10]

                        if has_phone:
                            action_id = 3
                            action_name = 'Dung dien thoai'
                            status_key = 'using_phone'
                            box_color = (40, 40, 235)
                        else:
                            is_raising = False
                            if l_wrist[2] > 0.20:
                                if (l_shoulder[2] > 0.20 and l_wrist[1] < l_shoulder[1] + 25) or (l_elbow[2] > 0.20 and l_wrist[1] < l_elbow[1]):
                                    is_raising = True
                            if r_wrist[2] > 0.20:
                                if (r_shoulder[2] > 0.20 and r_wrist[1] < r_shoulder[1] + 25) or (r_elbow[2] > 0.20 and r_wrist[1] < r_elbow[1]):
                                    is_raising = True

                            if is_raising:
                                action_id = 0
                                action_name = 'Gio tay'
                                status_key = 'raising_hand'
                                box_color = (46, 204, 113)
                            else:
                                action_id = 2
                                action_name = 'Hoc bai'
                                status_key = 'studying'
                                box_color = (182, 89, 155)

                        stats[action_id] = stats.get(action_id, 0) + 1

                        if self.demo_mode and total == 1:
                            target_seat = self.demo_seat_code
                            demo_user_detected = True
                            demo_user_action = action_name
                            demo_user_status = status_key
                            seat_label = f'[DEMO] {target_seat}: {action_name}'
                        else:
                            target_seat = map_coord_to_seat(norm_x, norm_y, self.total_rows, self.total_cols, self.seats_per_desk)
                            seat_label = f'{target_seat}: {action_name}'

                        seat_updates.append({
                            'seatCode': target_seat,
                            'status': status_key,
                            'action': action_name
                        })

                        cv2.rectangle(frame, (px1, py1), (px2, py2), box_color, 2)
                        (lw, lh), _ = cv2.getTextSize(seat_label, cv2.FONT_HERSHEY_SIMPLEX, 0.50, 2)
                        cv2.rectangle(frame, (px1, max(0, py1 - 24)), (px1 + lw + 12, py1), box_color, -1)
                        cv2.putText(frame, seat_label, (px1 + 4, max(16, py1 - 7)),
                                    cv2.FONT_HERSHEY_SIMPLEX, 0.50, (255, 255, 255), 2)

                        for p1, p2 in SKELETON_EDGES:
                            if kpts[p1][2] > 0.35 and kpts[p2][2] > 0.35:
                                cv2.line(frame, (int(kpts[p1][0]), int(kpts[p1][1])),
                                                (int(kpts[p2][0]), int(kpts[p2][1])), (0, 255, 255), 2)
                        for kp in kpts[:11]:
                            if kp[2] > 0.35:
                                cv2.circle(frame, (int(kp[0]), int(kp[1])), 4, (0, 0, 255), -1)

            # 5. Neu chi gio dien thoai truoc camera ma khong ro nguoi (Solo Demo)
            if total == 0 and has_phone and self.demo_mode:
                total = 1
                stats[3] = 1
                demo_user_detected = True
                demo_user_action = 'Dung dien thoai'
                demo_user_status = 'using_phone'
                seat_updates.append({
                    'seatCode': self.demo_seat_code,
                    'status': 'using_phone',
                    'action': 'Dung dien thoai'
                })

            # Ve hop thoai do ruc ro truc tiep len chiec dien thoai
            for (ph_box, ph_conf) in detected_phones:
                cv2.rectangle(frame, (ph_box[0], ph_box[1]), (ph_box[2], ph_box[3]), (40, 40, 235), 2)
                lbl = f'Dung dien thoai {int(ph_conf*100)}%'
                (lw, lh), _ = cv2.getTextSize(lbl, cv2.FONT_HERSHEY_SIMPLEX, 0.45, 1)
                cv2.rectangle(frame, (ph_box[0], max(0, ph_box[1] - 20)), (ph_box[0] + lw + 8, ph_box[1]), (40, 40, 235), -1)
                cv2.putText(frame, lbl, (ph_box[0] + 4, max(14, ph_box[1] - 5)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1)

            # 6. Dong bo du lieu thuc ve Backend (Loai bo hoan toan mo phong gia lap)
            self.phone_detected_alert = has_phone
            pos = stats.get(0, 0) + stats.get(1, 0) + stats.get(2, 0)
            score = round((pos / max(1, total)) * 100, 1) if total > 0 else 100.0

            self.stats = stats
            self.total_students = total
            self.attention_score = score
            if seat_updates:
                self._sync_to_backend(seat_updates, stats, total, score)

            # Ve HUD
            self._draw_hud(frame)

            # Nen JPEG chat luong 70
            ret_enc, jpeg = cv2.imencode('.jpg', frame, [int(cv2.IMWRITE_JPEG_QUALITY), 70])
            if ret_enc:
                with self.lock:
                    self.current_frame = jpeg.tobytes()
                    self.frame_id = (self.frame_id + 1) % 1000000

                now_f = time.time()
                if now_f - getattr(self, 'last_frame_post', 0) > 0.18:
                    self.last_frame_post = now_f
                    f_bytes = self.current_frame
                    def _post_f(b):
                        try:
                            requests.post(f'{BACKEND_BASE_URL}/api/device/camera-frame',
                                          data=b,
                                          headers={'X-Device-Key': DEVICE_KEY, 'Content-Type': 'image/jpeg'},
                                          timeout=0.8)
                        except Exception:
                            pass
                    threading.Thread(target=_post_f, args=(f_bytes,), daemon=True).start()

        self.is_running = False
        self.mode = 'idle'

    def _draw_hud(self, frame):
        h, w, _ = frame.shape
        overlay = frame.copy()
        cv2.rectangle(overlay, (10, 10), (340, 250), (20, 28, 38), -1)
        cv2.addWeighted(overlay, 0.78, frame, 0.22, 0, frame)
        cv2.rectangle(frame, (10, 10), (340, 250), (60, 85, 110), 1)

        mode_str = f'DEMO WEBCAM ({self.demo_seat_code})' if self.demo_mode else 'DA NGUOI (CAMERA LOP)'
        cv2.putText(frame, f'{self.room_code} - {mode_str}', (20, 35),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.50, (255, 255, 255), 2)
        cv2.putText(frame, f'{int(self.fps)} FPS', (280, 35),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.45, (160, 180, 200), 1)

        sc_col = (46, 204, 113) if self.attention_score >= 70 else (34, 126, 230) if self.attention_score >= 45 else (40, 40, 235)
        cv2.putText(frame, f'Tap trung: {self.attention_score:.1f}%', (20, 68),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.75, sc_col, 2)
        cv2.putText(frame, f'Si so lop: {self.total_students} hoc sinh ({self.total_rows}x{self.total_cols})', (20, 95),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.45, (220, 220, 220), 1)

        cv2.line(frame, (20, 106), (330, 106), (80, 100, 120), 1)

        rows = [
            ('Gio tay', self.stats.get(0, 0), (46, 204, 113)),
            ('Doc sach', self.stats.get(1, 0), (52, 152, 219)),
            ('Viet / Nghe', self.stats.get(2, 0), (155, 89, 182)),
            ('Dung dien thoai', self.stats.get(3, 0), (40, 40, 235)),
            ('Guc dau', self.stats.get(4, 0), (34, 126, 230)),
            ('Nam bo ban', self.stats.get(5, 0), (15, 196, 241)),
        ]
        y = 125
        for name, cnt, col in rows:
            cv2.circle(frame, (26, y - 4), 4, col, -1)
            cv2.putText(frame, f'{name}:', (36, y), cv2.FONT_HERSHEY_SIMPLEX, 0.40, (200, 210, 220), 1)
            cv2.putText(frame, str(cnt), (300, y), cv2.FONT_HERSHEY_SIMPLEX, 0.44, (255, 255, 255), 2)
            y += 18

        if self.phone_detected_alert or self.stats.get(3, 0) > 0:
            cv2.rectangle(frame, (w - 410, 15), (w - 15, 65), (20, 20, 200), -1)
            cv2.rectangle(frame, (w - 410, 15), (w - 15, 65), (100, 100, 255), 2)
            cv2.putText(frame, 'CANH BAO: PHAT HIEN DUNG DIEN THOAI!',
                        (w - 395, 45), cv2.FONT_HERSHEY_SIMPLEX, 0.46, (255, 255, 255), 2)


processor = VideoProcessor()


def generate_frames():
    last_id = -1
    while True:
        with processor.lock:
            frame = processor.current_frame
            fid = getattr(processor, 'frame_id', 0)

        if frame is not None:
            if fid != last_id:
                last_id = fid
                yield (b'--frame\r\n'
                       b'Content-Type: image/jpeg\r\n\r\n' + frame + b'\r\n')
            time.sleep(0.005)
        else:
            blank = np.zeros((480, 640, 3), dtype=np.uint8)
            cv2.putText(blank, 'CAMERA DANG TAT', (200, 220),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.8, (120, 140, 160), 2)
            cv2.putText(blank, 'Nhan Bat Webcam de thuyet trinh hoac Tai video', (120, 260),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (90, 105, 120), 1)
            ret_enc, blank_jpg = cv2.imencode('.jpg', blank)
            yield (b'--frame\r\n'
                   b'Content-Type: image/jpeg\r\n\r\n' + blank_jpg.tobytes() + b'\r\n')
            time.sleep(0.3)


@app.route('/video_feed')
def video_feed():
    resp = Response(generate_frames(), mimetype='multipart/x-mixed-replace; boundary=frame')
    resp.headers['Cache-Control'] = 'no-cache, no-store, must-revalidate, pre-check=0, post-check=0, max-age=0'
    resp.headers['Pragma'] = 'no-cache'
    resp.headers['Expires'] = '0'
    resp.headers['X-Accel-Buffering'] = 'no'
    return resp


@app.route('/api/webcam/start', methods=['POST'])
def start_webcam():
    data = request.get_json(silent=True) or {}
    cam_id = int(data.get('source', 0))
    room_code = data.get('room', 'A101')
    demo_mode = data.get('demoMode', True)
    demo_seat = data.get('demoSeat', 'B1-D1L')
    ok, msg = processor.start_webcam(cam_id, room_code, demo_mode, demo_seat)
    return jsonify({
        'success': ok,
        'message': msg,
        'mode': processor.mode,
        'demoMode': processor.demo_mode,
        'demoSeat': processor.demo_seat_code
    })


@app.route('/api/webcam/stop', methods=['POST'])
def stop_feed():
    processor.stop()
    return jsonify({'success': True, 'message': 'Da tat luong video'})


@app.route('/api/demo_mode', methods=['POST'])
def set_demo_mode():
    data = request.get_json(silent=True) or {}
    if 'demoMode' in data:
        processor.demo_mode = bool(data['demoMode'])
    if 'demoSeat' in data and data['demoSeat']:
        processor.demo_seat_code = str(data['demoSeat'])
    if 'rows' in data and data['rows']:
        processor.total_rows = int(data['rows'])
    if 'cols' in data and data['cols']:
        processor.total_cols = int(data['cols'])
    if 'seatsPerDesk' in data and data['seatsPerDesk']:
        processor.seats_per_desk = int(data['seatsPerDesk'])
    if 'rows' not in data and 'cols' not in data:
        processor.fetch_room_layout()
    return jsonify({
        'success': True,
        'demoMode': processor.demo_mode,
        'demoSeat': processor.demo_seat_code,
        'totalRows': processor.total_rows,
        'totalCols': processor.total_cols,
        'seatsPerDesk': processor.seats_per_desk
    })


@app.route('/api/video/upload', methods=['POST'])
def upload_video():
    if 'video' not in request.files:
        return jsonify({'success': False, 'message': 'Khong tim thay file video'}), 400
    file = request.files['video']
    if file.filename == '':
        return jsonify({'success': False, 'message': 'Chua chon file'}), 400

    filename = secure_filename(file.filename)
    save_path = os.path.join(app.config['UPLOAD_FOLDER'], filename)
    file.save(save_path)

    room_code = request.form.get('room', 'A101')
    ok, msg = processor.start_video(save_path, room_code)
    return jsonify({'success': ok, 'message': msg, 'filename': filename})


@app.route('/api/status', methods=['GET'])
def get_status():
    return jsonify({
        'running': processor.is_running,
        'mode': processor.mode,
        'room': processor.room_code,
        'demoMode': processor.demo_mode,
        'demoSeat': processor.demo_seat_code,
        'fps': round(processor.fps, 1),
        'totalStudents': processor.total_students,
        'attentionScore': round(processor.attention_score, 1),
        'behaviors': processor.stats
    })


def _cloud_listener():
    """Lang nghe lenh Bat/Tat Webcam truc tiep tu Web Dashboard va gui Heartbeat"""
    print(f'[INFO] AI Server da ket noi Cloud: {BACKEND_BASE_URL}')
    print('[INFO] Che do CHO LENH: Chi bat webcam khi ban nhan nut tren Web Dashboard!')
    while True:
        try:
            time.sleep(1.0)
            status_payload = {
                'running': processor.is_running,
                'mode': processor.mode,
                'room': processor.room_code,
                'demoMode': processor.demo_mode,
                'demoSeat': processor.demo_seat_code,
                'fps': round(processor.fps, 1),
                'totalStudents': processor.total_students,
                'attentionScore': round(processor.attention_score, 1)
            }
            r = requests.post(f'{BACKEND_BASE_URL}/api/device/camera-heartbeat',
                              json=status_payload,
                              headers={'X-Device-Key': DEVICE_KEY, 'Content-Type': 'application/json'},
                              timeout=2.0)
            if r.status_code == 200:
                cmd = r.json().get('command', {})
                cmd_active = cmd.get('active', False)
                if cmd_active and not processor.is_running:
                    room = cmd.get('roomCode', 'A101')
                    demo_mode = cmd.get('demoMode', True)
                    demo_seat = cmd.get('demoSeat', 'B1-D1L')
                    print(f'\n>>> [LENH TU WEB] NHAN LENH BAT WEBCAM (Phong: {room}, Cho: {demo_seat}) <<<')
                    processor.start_webcam(0, room, demo_mode, demo_seat)
                elif not cmd_active and processor.is_running:
                    print('\n>>> [LENH TU WEB] NHAN LENH TAT WEBCAM <<<')
                    processor.stop()
        except Exception:
            pass


if __name__ == '__main__':
    print('=' * 60)
    print('  SMART CLASSROOM HYBRID AI SERVER - DEMO & REALTIME ENGINE')
    print('  * Ho tro che do Demo Laptop Webcam ca nhan (Solo Demo)')
    print('  * Phat hien Dien thoai & Hanh vi TUC THI (In-line Frame Analysis)')
    print('  * Xu ly camera buffer khong do tre voi RealtimeCapture')
    print(f'  * Cloud endpoint: {BACKEND_BASE_URL}')
    print('  * Video stream:  http://localhost:5000/video_feed')
    print('  * Status API:    http://localhost:5000/api/status')
    print('  * CHE DO: CHO LENH BAT WEBCAM TU WEB (KHONG TU DONG BAT)')
    print('=' * 60)

    threading.Thread(target=_cloud_listener, daemon=True).start()
    app.run(host='0.0.0.0', port=5000, debug=False, threaded=True)
