"""
Smart Classroom AI Server - Hybrid Intelligent Behavior & Dynamic Seating Map Engine
Ket hop YOLO11 Pose Estimation + YOLO11 Object Detection
Ho tro 2 che do:
 1. Che do Thuyet trinh / Solo Demo (Toi uu tuyet doi khi thuyet trinh bang Webcam laptop ca nhan)
 2. Che do Lop hoc thuc te da nguoi voi Luoi toa do dong (Dynamic Grid Layout)
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

# Toi uu hoa luong CPU cho PyTorch tren Windows
torch.set_num_threads(min(4, os.cpu_count() or 4))

app = Flask(__name__)
CORS(app)

UPLOAD_FOLDER = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'uploads')
os.makedirs(UPLOAD_FOLDER, exist_ok=True)
app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER
app.config['MAX_CONTENT_LENGTH'] = 500 * 1024 * 1024

script_dir = os.path.dirname(os.path.abspath(__file__))
pose_path = os.path.join(script_dir, 'yolo11n-pose.pt')
obj_path = os.path.join(script_dir, 'yolo11n.pt')

print('[INFO] Dang khoi dong YOLO11 Pose va Object Detection...')
pose_model = YOLO(pose_path)
obj_model = YOLO(obj_path)
print('[INFO] Mo hinh AI da san sang!')

SPRING_BOOT_URL = 'http://localhost:8080/api/device/behavior'
SPRING_BOOT_SEATS_URL = 'http://localhost:8080/api/device/seats'
DEVICE_KEY = 'esp32-secret-2026'

SKELETON_EDGES = [
    (0, 1), (0, 2), (1, 3), (2, 4),
    (5, 6), (5, 7), (7, 9),
    (6, 8), (8, 10),
    (5, 11), (6, 12), (11, 12)
]

def map_coord_to_seat(norm_x, norm_y, total_rows=5, total_cols=4, seats_per_desk=2):
    """
    Anh xa toa do chuan hoa (0.0 - 1.0) vao ma ghe theo cau hinh phong dong.
    """
    col = min(total_cols, max(1, int(norm_x * total_cols) + 1))
    row = min(total_rows, max(1, int(norm_y * total_rows) + 1))

    if seats_per_desk == 1:
        return f'B{row}-D{col}'

    cell_width = 1.0 / total_cols
    local_x = (norm_x - (col - 1) * cell_width) / cell_width
    side = 'L' if local_x < 0.5 else 'R'
    return f'B{row}-D{col}{side}'


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

        # Cau hinh dong cua lop hoc
        self.total_rows = 5
        self.total_cols = 4
        self.seats_per_desk = 2

        # Che do thuyet trinh / Solo Demo (ho tro tot nhat khi demo bang webcam laptop)
        self.demo_mode = True
        self.demo_seat_code = 'B1-D1L'
        self.ambient_activity = True
        self.ambient_seats_cache = []
        self.last_ambient_tick = 0

    def fetch_room_layout(self):
        """Lay cau hinh phong hoc tu Spring Boot backend"""
        try:
            res = requests.get('http://localhost:8080/api/rooms', timeout=1.5)
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
        if sys.platform.startswith('win'):
            self.cap = cv2.VideoCapture(cam_id, cv2.CAP_DSHOW)
        if self.cap is None or not self.cap.isOpened():
            self.cap = cv2.VideoCapture(cam_id)
        if not self.cap or not self.cap.isOpened():
            self.mode = 'idle'
            return False, 'Khong the mo Webcam'

        # Toi uu toc do truyen va giam do tre (Latency & Buffer) tren Windows
        self.cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        self.cap.set(cv2.CAP_PROP_FPS, 30)
        self.cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

        self.is_running = True
        self.thread = threading.Thread(target=self._process_loop, daemon=True)
        self.thread.start()
        return True, 'Mo Webcam thanh cong (Che do Demo: ' + ('Bat' if self.demo_mode else 'Tat') + ')'

    def start_video(self, video_path, room_code='A101'):
        self.stop()
        self.room_code = room_code
        self.demo_mode = False  # Video lop hoc thuong la nhieu nguoi
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
            self.thread.join(timeout=1.0)
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
        if now - self.last_sync_time < 1.5:
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
                # 1. Gui log hanh vi tong the
                requests.post(SPRING_BOOT_URL, json=payload_behavior,
                              headers={'X-Device-Key': DEVICE_KEY, 'Content-Type': 'application/json'},
                              timeout=1.5)
                # 2. Gui trang thai chi tiet tung ghe
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

    def _generate_ambient_seats(self, demo_seat_code, demo_status, demo_action):
        """Sinh trang thai hoc tap sinh dong cho ca lop trong khi thuyet trinh 1 nguoi"""
        now = time.time()
        total_desks = self.total_rows * self.total_cols
        total_seats = total_desks * self.seats_per_desk

        # Cap nhat ngau nhien sau moi 3 giay de lop hoc co nhip sinh dong
        if now - self.last_ambient_tick > 3.0 or not self.ambient_seats_cache:
            self.last_ambient_tick = now
            cache = []
            idx = 1
            for r in range(1, self.total_rows + 1):
                for c in range(1, self.total_cols + 1):
                    sides = ['L', 'R'] if self.seats_per_desk == 2 else ['']
                    for s in sides:
                        code = f'B{r}-D{c}{s}'
                        if code == demo_seat_code:
                            continue
                        # Ty le: 70% Hoc bai, 15% Chep bai, 10% Doc sach, 5% Gio tay
                        rnd = random.random()
                        if rnd < 0.70:
                            st, act = 'studying', 'Hoc bai'
                        elif rnd < 0.85:
                            st, act = 'studying', 'Viet bai'
                        elif rnd < 0.95:
                            st, act = 'studying', 'Doc sach'
                        else:
                            st, act = 'raising_hand', 'Gio tay'
                        cache.append({'seatCode': code, 'status': st, 'action': act})
                        idx += 1
            self.ambient_seats_cache = cache

        # Ket hop ghe demo cua nguoi thuyet trinh
        res = list(self.ambient_seats_cache)
        res.append({'seatCode': demo_seat_code, 'status': demo_status, 'action': demo_action})
        return res

    def _process_loop(self, loop_path=None):
        prev_time = time.time()
        frame_idx = 0
        cached_phones = []
        cached_books = []

        while self.is_running and self.cap:
            ret, frame = self.cap.read()
            if not ret:
                if loop_path and os.path.exists(loop_path):
                    self.cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                    continue
                else:
                    break

            frame_idx += 1
            h, w, _ = frame.shape
            curr_time = time.time()
            self.fps = 1.0 / max(0.001, curr_time - prev_time)
            prev_time = curr_time

            # 1. Phat hien dien thoai va sach qua Object Detection
            # Chay xen ke moi 2 frame hoac khi co dien thoai de giam 50% tai CPU, FPS tang gap doi
            if frame_idx % 2 == 0 or len(cached_phones) > 0:
                obj_results = obj_model.predict(frame, classes=[67, 73], conf=0.20, imgsz=320, verbose=False)
                new_phones = []
                new_books = []
                if len(obj_results) > 0 and obj_results[0].boxes is not None:
                    for b in obj_results[0].boxes:
                        cid = int(b.cls[0].item())
                        box = b.xyxy[0].cpu().numpy().astype(int)
                        conf = float(b.conf[0].item())
                        if cid == 67:
                            new_phones.append((box, conf))
                        elif cid == 73:
                            new_books.append((box, conf))
                cached_phones = new_phones
                cached_books = new_books

            detected_phones = cached_phones
            detected_books = cached_books

            # 2. Phat hien nguoi va khung xuong qua Pose Model voi imgsz=320 sieu nhe, toc do 25-30 FPS
            pose_results = pose_model.predict(frame, conf=0.35, imgsz=320, verbose=False)

            stats = {0: 0, 1: 0, 2: 0, 3: 0, 4: 0, 5: 0}
            total = 0
            seat_updates = []
            demo_user_detected = False
            demo_user_action = 'Hoc bai'
            demo_user_status = 'studying'

            if len(pose_results) > 0 and pose_results[0].boxes is not None and pose_results[0].keypoints is not None:
                p_boxes = pose_results[0].boxes.xyxy.cpu().numpy().astype(int)
                kpts_data = pose_results[0].keypoints.data.cpu().numpy()

                for i, p_box in enumerate(p_boxes):
                    total += 1
                    kpts = kpts_data[i]
                    px1, py1, px2, py2 = p_box

                    cx = (px1 + px2) / 2.0
                    cy = (py1 + py2) / 2.0
                    norm_x = cx / max(1, w)
                    norm_y = cy / max(1, h)

                    # Kiem tra co dien thoai gan nguoi nay khong
                    has_phone = False
                    for (ph_box, ph_conf) in detected_phones:
                        ph_cx = (ph_box[0] + ph_box[2]) / 2
                        ph_cy = (ph_box[1] + ph_box[3]) / 2
                        if (px1 - 40 <= ph_cx <= px2 + 40) and (py1 <= ph_cy <= py2 + 60):
                            has_phone = True
                            break

                    nose = kpts[0]
                    l_shoulder, r_shoulder = kpts[5], kpts[6]
                    l_wrist, r_wrist = kpts[9], kpts[10]

                    if has_phone:
                        action_id = 3
                        action_name = 'Dung dien thoai'
                        status_key = 'using_phone'
                        box_color = (40, 40, 235)  # Do
                    else:
                        is_raising = False
                        if l_wrist[2] > 0.4 and l_shoulder[2] > 0.4 and l_wrist[1] < l_shoulder[1]:
                            is_raising = True
                        if r_wrist[2] > 0.4 and r_shoulder[2] > 0.4 and r_wrist[1] < r_shoulder[1]:
                            is_raising = True

                        if is_raising:
                            action_id = 0
                            action_name = 'Gio tay'
                            status_key = 'raising_hand'
                            box_color = (46, 204, 113)  # Xanh la
                        else:
                            avg_shoulder_y = (l_shoulder[1] + r_shoulder[1]) / 2 if (l_shoulder[2] > 0.3 and r_shoulder[2] > 0.3) else py1 + (py2 - py1) * 0.3
                            head_drop = (nose[1] - avg_shoulder_y) if nose[2] > 0.3 else 0

                            if head_drop > 25:
                                action_id = 5
                                action_name = 'Nam bo ban'
                                status_key = 'bowing_head'
                                box_color = (15, 196, 241)  # Vang cam
                            elif head_drop > 5:
                                action_id = 4
                                action_name = 'Guc dau'
                                status_key = 'bowing_head'
                                box_color = (34, 126, 230)  # Cam
                            else:
                                has_book = False
                                for (bk_box, _) in detected_books:
                                    bk_cx = (bk_box[0] + bk_box[2]) / 2
                                    bk_cy = (bk_box[1] + bk_box[3]) / 2
                                    if (px1 - 20 <= bk_cx <= px2 + 20) and (py1 <= bk_cy <= py2 + 80):
                                        has_book = True
                                        break
                                if has_book:
                                    action_id = 1
                                    action_name = 'Doc sach'
                                    status_key = 'studying'
                                    box_color = (219, 152, 52)
                                else:
                                    action_id = 2
                                    action_name = 'Hoc bai'
                                    status_key = 'studying'
                                    box_color = (182, 89, 155)

                    stats[action_id] = stats.get(action_id, 0) + 1

                    # Xac dinh ma ghe: Neu Solo Demo Mode va chi co 1 nguoi thi map truc tiep vao demo_seat_code
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

                    # Ve Bounding Box va Khung xuong
                    cv2.rectangle(frame, (px1, py1), (px2, py2), box_color, 2)

                    # Label tren dau
                    (lw, lh), _ = cv2.getTextSize(seat_label, cv2.FONT_HERSHEY_SIMPLEX, 0.52, 2)
                    cv2.rectangle(frame, (px1, max(0, py1 - 26)), (px1 + lw + 14, py1), box_color, -1)
                    cv2.putText(frame, seat_label, (px1 + 6, max(18, py1 - 8)),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.52, (255, 255, 255), 2)

                    # Ve xuong khop
                    for p1, p2 in SKELETON_EDGES:
                        if kpts[p1][2] > 0.4 and kpts[p2][2] > 0.4:
                            pt1 = (int(kpts[p1][0]), int(kpts[p1][1]))
                            pt2 = (int(kpts[p2][0]), int(kpts[p2][1]))
                            cv2.line(frame, pt1, pt2, (0, 255, 255), 2)

                    for kp in kpts[:11]:
                        if kp[2] > 0.4:
                            cv2.circle(frame, (int(kp[0]), int(kp[1])), 4, (0, 0, 255), -1)

            # Ve hop thoai dien thoai
            for (ph_box, ph_conf) in detected_phones:
                cv2.rectangle(frame, (ph_box[0], ph_box[1]), (ph_box[2], ph_box[3]), (0, 0, 255), 2)
                cv2.putText(frame, f'DT {int(ph_conf*100)}%', (ph_box[0], max(15, ph_box[1] - 5)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 0, 255), 2)

            # Xu ly tong the khi Solo Demo Mode co Ambient
            if self.demo_mode and self.ambient_activity:
                full_seat_updates = self._generate_ambient_seats(self.demo_seat_code, demo_user_status, demo_user_action)
                display_total = len(full_seat_updates)
                # Tinh stats gia lap tong the de HUD dep mat
                ambient_stats = {0: 0, 1: 0, 2: 0, 3: 0, 4: 0, 5: 0}
                for su in full_seat_updates:
                    act = su.get('action', '')
                    if 'Dung dien thoai' in act: ambient_stats[3] += 1
                    elif 'Gio tay' in act: ambient_stats[0] += 1
                    elif 'Doc sach' in act: ambient_stats[1] += 1
                    elif 'Viet bai' in act: ambient_stats[2] += 1
                    elif 'Guc dau' in act: ambient_stats[4] += 1
                    elif 'Nam bo ban' in act: ambient_stats[5] += 1
                    else: ambient_stats[2] += 1

                pos = ambient_stats.get(0, 0) + ambient_stats.get(1, 0) + ambient_stats.get(2, 0)
                score = round((pos / max(1, display_total)) * 100, 1)

                self.stats = ambient_stats
                self.total_students = display_total
                self.attention_score = score
                self._sync_to_backend(full_seat_updates, ambient_stats, display_total, score)
            else:
                self.stats = stats
                self.total_students = total
                pos = stats.get(0, 0) + stats.get(1, 0) + stats.get(2, 0)
                self.attention_score = round((pos / max(1, total)) * 100, 1) if total > 0 else 0.0
                self._sync_to_backend(seat_updates, stats, total, self.attention_score)

            # Ve HUD thong so va huong dan len frame
            self._draw_hud(frame)

            # Nen JPEG chat luong 70 de truyen tai sieu nhanh khong bi giat
            ret_enc, jpeg = cv2.imencode('.jpg', frame, [int(cv2.IMWRITE_JPEG_QUALITY), 70])
            if ret_enc:
                with self.lock:
                    self.current_frame = jpeg.tobytes()

        self.is_running = False
        self.mode = 'idle'

    def _draw_hud(self, frame):
        h, w, _ = frame.shape
        overlay = frame.copy()
        cv2.rectangle(overlay, (10, 10), (340, 250), (20, 28, 38), -1)
        cv2.addWeighted(overlay, 0.78, frame, 0.22, 0, frame)
        cv2.rectangle(frame, (10, 10), (340, 250), (60, 85, 110), 1)

        # Title & Mode
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

        # Canh bao dung DT
        if self.stats.get(3, 0) > 0:
            cv2.rectangle(frame, (w - 410, 15), (w - 15, 65), (20, 20, 200), -1)
            cv2.rectangle(frame, (w - 410, 15), (w - 15, 65), (100, 100, 255), 2)
            cv2.putText(frame, 'CANH BAO: PHAT HIEN DUNG DIEN THOAI!',
                        (w - 395, 45), cv2.FONT_HERSHEY_SIMPLEX, 0.46, (255, 255, 255), 2)


processor = VideoProcessor()


def generate_frames():
    while True:
        with processor.lock:
            frame = processor.current_frame
        if frame is not None:
            yield (b'--frame\r\n'
                   b'Content-Type: image/jpeg\r\n\r\n' + frame + b'\r\n')
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
        time.sleep(0.01)


@app.route('/video_feed')
def video_feed():
    return Response(generate_frames(), mimetype='multipart/x-mixed-replace; boundary=frame')


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
    if 'ambient' in data:
        processor.ambient_activity = bool(data['ambient'])
    if 'rows' in data and data['rows']:
        processor.total_rows = int(data['rows'])
    if 'cols' in data and data['cols']:
        processor.total_cols = int(data['cols'])
    if 'seatsPerDesk' in data and data['seatsPerDesk']:
        processor.seats_per_desk = int(data['seatsPerDesk'])
    processor.ambient_seats_cache = []
    if 'rows' not in data and 'cols' not in data:
        processor.fetch_room_layout()
    return jsonify({
        'success': True,
        'demoMode': processor.demo_mode,
        'demoSeat': processor.demo_seat_code,
        'ambient': processor.ambient_activity,
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


if __name__ == '__main__':
    print('=' * 60)
    print('  SMART CLASSROOM HYBRID AI SERVER - DEMO & REALTIME ENGINE')
    print('  * Ho tro che do Demo Laptop Webcam ca nhan (Solo Demo)')
    print('  * Ho tro cau hinh quy mo phong hoc dong (Dynamic Layout)')
    print('  * Video stream:  http://localhost:5000/video_feed')
    print('  * Status API:    http://localhost:5000/api/status')
    print('=' * 60)
    app.run(host='0.0.0.0', port=5000, debug=False, threaded=True)
