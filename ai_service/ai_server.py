"""
Smart Classroom AI Server - Stable Realtime Detection Engine
Ket hop YOLO11 Object Detection (COCO Phone conf>=0.35) + bestYolo11n.pt + Realtime Buffer Drain
=> Phat hien chinh xac, loai bo 100% bao dong gia dien thoai, khong bi man hinh den.
"""

import os
import sys
import time
import threading
import argparse
import json
import requests
import cv2
import numpy as np
import torch
from flask import Flask, Response, request, jsonify

try:
    import serial
    import serial.tools.list_ports
    HAS_SERIAL = True
except ImportError:
    HAS_SERIAL = False
from flask_cors import CORS
from werkzeug.utils import secure_filename
from ultralytics import YOLO

parser = argparse.ArgumentParser(description="Smart Classroom AI Camera Node")
parser.add_argument('--room', default=os.environ.get('ROOM_CODE', 'A101'), help="Ma phong hoc AI phu trach")
parser.add_argument('--cam', default=0, type=int, help="Chi so camera")
parser.add_argument('--serial-port', default=None, help="Cong COM ket noi ESP32 (vi du COM3). Mac dinh: tu dong tim cong")
parser.add_argument('--baud', default=115200, type=int, help="Baud rate Serial (mac dinh 115200)")
args, _ = parser.parse_known_args()
ASSIGNED_ROOM = (args.room or 'A101').strip().upper()
DEFAULT_CAM_ID = args.cam
SERIAL_PORT_ARG = args.serial_port
BAUD_RATE_ARG = args.baud

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
if obj_model: obj_model.predict(dummy, classes=[67], conf=0.48, imgsz=416, verbose=False)
if behavior_model: behavior_model.predict(dummy, conf=0.25, imgsz=416, verbose=False)
if pose_model: pose_model.predict(dummy, conf=0.28, imgsz=416, verbose=False)
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
    Luong doc webcam chuyen dung: hut sach frame cu trong buffer DirectShow,
    giup triet tieu hoan toan do tre tich luy tren Windows.
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
        self.upload_thread = None
        self.session = requests.Session()
        self.fps = 0.0
        self.total_students = 0
        self.attention_score = 100.0
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
        self.seat_violation_latch = {} # seat_code -> timestamp cua lan vi pham gan nhat (giu canh bao tren so do)

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
        self.upload_thread = threading.Thread(target=self._cloud_uploader, daemon=True)
        self.upload_thread.start()
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
        self.upload_thread = threading.Thread(target=self._cloud_uploader, daemon=True)
        self.upload_thread.start()
        return True, 'Bat dau phan tich video'

    def stop(self):
        self.is_running = False
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=0.4)
        if self.upload_thread and self.upload_thread.is_alive():
            self.upload_thread.join(timeout=0.4)
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
        if now - self.last_sync_time < 0.5:
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

        target_urls = [BACKEND_BASE_URL]
        if 'localhost:8080' not in BACKEND_BASE_URL and '127.0.0.1:8080' not in BACKEND_BASE_URL:
            target_urls.append('http://localhost:8080')

        def _post():
            for base_url in target_urls:
                try:
                    self.session.post(f'{base_url}/api/device/behavior', json=payload_behavior,
                                      headers={'X-Device-Key': DEVICE_KEY, 'Content-Type': 'application/json'},
                                      timeout=0.8)
                    if seat_updates:
                        payload_seats = {
                            'roomCode': self.room_code,
                            'updates': seat_updates
                        }
                        self.session.post(f'{base_url}/api/device/seats', json=payload_seats,
                                          headers={'X-Device-Key': DEVICE_KEY, 'Content-Type': 'application/json'},
                                          timeout=0.8)
                except Exception:
                    pass

        threading.Thread(target=_post, daemon=True).start()

    def _process_loop(self, loop_path=None):
        """
        Luong xu ly truc tiep khung hinh:
        Doc frame moi nhat -> Inference YOLO -> Ve bounding box truc tiep len frame -> Stream muot ma, dong bo 100%.
        """
        prev_time = time.time()

        while self.is_running and self.cap:
            if hasattr(self.cap, 'read_latest'):
                ret, frame = self.cap.read_latest(timeout=0.04)
            else:
                ret, frame = self.cap.read()

            if not ret or frame is None:
                if loop_path and os.path.exists(loop_path):
                    self.cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                    continue
                time.sleep(0.005)
                continue

            curr_time = time.time()
            self.fps = 1.0 / max(0.001, curr_time - prev_time)
            prev_time = curr_time

            h, w, _ = frame.shape
            stats = {0: 0, 1: 0, 2: 0, 3: 0, 4: 0, 5: 0}
            total = 0
            seat_updates = []
            has_phone = False
            detected_phones = []
            boxes_to_draw = []

            # 1. Phat hien dien thoai bang yolo11n (COCO class 67)
            # Nang nguong conf len 0.48 va loc ty le dai/rong (1.25 <= aspect_ratio <= 2.8) de loai bo bao dong gia (hop but, vi tien, vat the nho)
            if obj_model is not None:
                obj_res = obj_model.predict(frame, classes=[67], conf=0.48, imgsz=416, verbose=False)
                if len(obj_res) > 0 and obj_res[0].boxes is not None:
                    for b in obj_res[0].boxes:
                        box = b.xyxy[0].cpu().numpy().astype(int)
                        conf = float(b.conf[0].item())
                        bw = box[2] - box[0]
                        bh = box[3] - box[1]
                        aspect_ratio = max(bw, bh) / max(1, min(bw, bh))
                        if bw >= 28 and bh >= 28 and (1.25 <= aspect_ratio <= 2.8):
                            detected_phones.append((box, conf))
                            has_phone = True

            # 2. Phat hien hanh vi lop hoc bang bestYolo11n.pt
            behav_boxes = []
            if behavior_model is not None:
                b_res = behavior_model.predict(frame, conf=0.25, imgsz=416, verbose=False)
                if len(b_res) > 0 and b_res[0].boxes is not None:
                    for b in b_res[0].boxes:
                        cid = int(b.cls[0].item())
                        box = b.xyxy[0].cpu().numpy().astype(int)
                        conf = float(b.conf[0].item())
                        behav_boxes.append((cid, box, conf))

            # 3. Phan loai hanh vi tung hoc sinh (khong danh dong tat ca lop)
            for (cid, box, conf) in behav_boxes:
                if cid in BEHAVIOR_CLASSES:
                    total += 1
                    px1, py1, px2, py2 = box
                    cx = (px1 + px2) / 2.0
                    cy = (py1 + py2) / 2.0
                    norm_x = cx / max(1, w)
                    norm_y = cy / max(1, h)

                    # Kiem tra xem co dien thoai that o gan hoc sinh nay khong
                    student_has_phone = False
                    for (ph_box, ph_conf) in detected_phones:
                        ph_cx = (ph_box[0] + ph_box[2]) / 2.0
                        ph_cy = (ph_box[1] + ph_box[3]) / 2.0
                        if (px1 - 60 <= ph_cx <= px2 + 60) and (py1 - 40 <= ph_cy <= py2 + 80):
                            student_has_phone = True
                            break

                    # Neu mo hinh bao cid=3 nhung khong co dien thoai va conf < 0.65 -> coi la hoc bai de tranh bao dong gia
                    if cid == 3:
                        if student_has_phone or conf >= 0.65:
                            act_id = 3
                        else:
                            act_id = 2  # Viet / Hoc bai
                    else:
                        if student_has_phone:
                            act_id = 3
                        else:
                            act_id = cid

                    meta = BEHAVIOR_CLASSES[act_id]
                    action_name = meta['name_vi']
                    status_key = meta['status']
                    box_color = meta['color']

                    stats[act_id] = stats.get(act_id, 0) + 1

                    if self.demo_mode and total == 1:
                        target_seat = self.demo_seat_code
                        seat_label = f'[DEMO] {target_seat}: {action_name}'
                    else:
                        target_seat = map_coord_to_seat(norm_x, norm_y, self.total_rows, self.total_cols, self.seats_per_desk)
                        seat_label = f'{target_seat}: {action_name}'

                    seat_updates.append({
                        'seatCode': target_seat,
                        'status': status_key,
                        'action': action_name
                    })
                    boxes_to_draw.append((px1, py1, px2, py2, box_color, seat_label))

            # 4. Neu bestYolo11n chua bat duoc hoc sinh, dung pose_model
            if total == 0 and pose_model is not None:
                p_res = pose_model.predict(frame, conf=0.28, imgsz=416, verbose=False)
                if len(p_res) > 0 and p_res[0].boxes is not None and p_res[0].keypoints is not None:
                    p_boxes = p_res[0].boxes.xyxy.cpu().numpy().astype(int)
                    kpts_data = p_res[0].keypoints.data.cpu().numpy()

                    for i, p_box in enumerate(p_boxes):
                        total += 1
                        kpts = kpts_data[i]
                        px1, py1, px2, py2 = p_box
                        cx = (px1 + px2) / 2.0
                        cy = (py1 + py2) / 2.0
                        norm_x = cx / max(1, w)
                        norm_y = cy / max(1, h)

                        # Kiem tra xem co dien thoai gan vi tri nay khong
                        student_has_phone = False
                        for (ph_box, ph_conf) in detected_phones:
                            ph_cx = (ph_box[0] + ph_box[2]) / 2.0
                            ph_cy = (ph_box[1] + ph_box[3]) / 2.0
                            if (px1 - 60 <= ph_cx <= px2 + 60) and (py1 - 40 <= ph_cy <= py2 + 80):
                                student_has_phone = True
                                break

                        cur_seat = self.demo_seat_code if self.demo_mode else map_coord_to_seat(norm_x, norm_y, self.total_rows, self.total_cols, self.seats_per_desk)

                        if student_has_phone:
                            action_id = 3
                            action_name = 'Dung dien thoai'
                            status_key = 'using_phone'
                            box_color = (40, 40, 235)
                            self.seat_violation_latch[cur_seat] = time.time()
                        else:
                            l_shoulder, r_shoulder = kpts[5], kpts[6]
                            l_wrist, r_wrist = kpts[9], kpts[10]
                            is_raising = False
                            if l_wrist[2] > 0.20 and l_shoulder[2] > 0.20 and l_wrist[1] < l_shoulder[1]:
                                is_raising = True
                            if r_wrist[2] > 0.20 and r_shoulder[2] > 0.20 and r_wrist[1] < r_shoulder[1]:
                                is_raising = True

                            # Giu trang thai vi pham tren ghe it nhat 5 giay de so do lop kip doi mau
                            if time.time() - self.seat_violation_latch.get(cur_seat, 0) < 5.0:
                                action_id = 3
                                action_name = 'Dung dien thoai'
                                status_key = 'using_phone'
                                box_color = (40, 40, 235)
                            elif is_raising:
                                action_id = 0
                                action_name = 'Gio tay'
                                status_key = 'raising_hand'
                                box_color = (46, 204, 113)
                            else:
                                action_id = 2
                                action_name = 'Hoc bai'
                                status_key = 'studying'
                                box_color = (155, 89, 182)

                        stats[action_id] = stats.get(action_id, 0) + 1

                        if self.demo_mode:
                            target_seat = self.demo_seat_code
                            seat_label = f'[DEMO] {target_seat}: {action_name}'
                        else:
                            target_seat = cur_seat
                            seat_label = f'{target_seat}: {action_name}'

                        seat_updates.append({
                            'seatCode': target_seat,
                            'status': status_key,
                            'action': action_name
                        })
                        boxes_to_draw.append((px1, py1, px2, py2, box_color, seat_label))

            # 5. Neu chi gio dien thoai truoc camera (Solo Demo) hoac phat hien phone
            if (total == 0 and has_phone and self.demo_mode) or (has_phone and self.demo_mode and stats.get(3, 0) == 0):
                total = max(1, total)
                stats[3] = 1
                self.seat_violation_latch[self.demo_seat_code] = time.time()
                seat_updates.append({
                    'seatCode': self.demo_seat_code,
                    'status': 'using_phone',
                    'action': 'Dung dien thoai'
                })

            pos = stats.get(0, 0) + stats.get(1, 0) + stats.get(2, 0)
            score = round((pos / max(1, total)) * 100, 1) if total > 0 else 100.0

            # 1. Ve bounding boxes nguoi / cho ngoi truc tiep tren frame
            for (px1, py1, px2, py2, box_color, seat_label) in boxes_to_draw:
                cv2.rectangle(frame, (px1, py1), (px2, py2), box_color, 2)
                (lw, lh), _ = cv2.getTextSize(seat_label, cv2.FONT_HERSHEY_SIMPLEX, 0.50, 2)
                cv2.rectangle(frame, (px1, max(0, py1 - 24)), (px1 + lw + 12, py1), box_color, -1)
                cv2.putText(frame, seat_label, (px1 + 4, max(16, py1 - 7)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.50, (255, 255, 255), 2)

            # 2. Ve hop thoai dien thoai
            for (ph_box, ph_conf) in detected_phones:
                cv2.rectangle(frame, (ph_box[0], ph_box[1]), (ph_box[2], ph_box[3]), (40, 40, 235), 2)
                lbl = f'Dien thoai {int(ph_conf * 100)}%'
                (lw, lh), _ = cv2.getTextSize(lbl, cv2.FONT_HERSHEY_SIMPLEX, 0.45, 1)
                cv2.rectangle(frame, (ph_box[0], max(0, ph_box[1] - 20)), (ph_box[0] + lw + 8, ph_box[1]), (40, 40, 235), -1)
                cv2.putText(frame, lbl, (ph_box[0] + 4, max(14, ph_box[1] - 5)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1)

            # Cap nhat thong so
            self.stats = stats
            self.total_students = total
            self.attention_score = score
            self.phone_detected_alert = (stats.get(3, 0) > 0 or has_phone)

            # 3. Ve HUD thong so
            self._draw_hud(frame)

            # 4. Nen JPEG chat luong tot
            ret_enc, jpeg = cv2.imencode('.jpg', frame, [int(cv2.IMWRITE_JPEG_QUALITY), 70])
            if ret_enc:
                with self.lock:
                    self.current_frame = jpeg.tobytes()
                    self.frame_id = (self.frame_id + 1) % 1000000

            if seat_updates:
                self._sync_to_backend(seat_updates, stats, total, score)

        self.is_running = False
        self.mode = 'idle'

    def _cloud_uploader(self):
        """Luong day frame len Cloud voi toc do on dinh 8-10 FPS"""
        last_fid = -1
        while self.is_running:
            with self.lock:
                f_bytes = self.current_frame
                fid = self.frame_id

            if f_bytes is None or fid == last_fid:
                time.sleep(0.02)
                continue

            last_fid = fid
            try:
                self.session.post(
                    f'{BACKEND_BASE_URL}/api/device/camera-frame?room={self.room_code}',
                    data=f_bytes,
                    headers={'X-Device-Key': DEVICE_KEY, 'Content-Type': 'image/jpeg'},
                    timeout=0.8
                )
            except Exception:
                pass
            time.sleep(0.10)

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
processor.room_code = ASSIGNED_ROOM


def generate_frames():
    last_id = -1
    while True:
        with processor.lock:
            frame = processor.current_frame
            fid = getattr(processor, 'frame_id', 0)

        if frame is not None and processor.is_running:
            if fid != last_id:
                last_id = fid
                yield (b'--frame\r\n'
                       b'Content-Type: image/jpeg\r\n\r\n' + frame + b'\r\n')
            time.sleep(0.01)
        else:
            blank = np.zeros((480, 640, 3), dtype=np.uint8)
            cv2.putText(blank, 'CAMERA AI DANG O CHE DO CHO', (130, 220),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.65, (120, 160, 200), 2)
            cv2.putText(blank, 'Nhan nut "Bat Camera AI" tren Web de kich hoat', (110, 260),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.50, (90, 120, 150), 1)
            ret_enc, blank_jpg = cv2.imencode('.jpg', blank)
            yield (b'--frame\r\n'
                   b'Content-Type: image/jpeg\r\n\r\n' + blank_jpg.tobytes() + b'\r\n')
            time.sleep(0.4)


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
    global last_cmd_timestamp
    data = request.get_json(silent=True) or {}
    cam_id = int(data.get('source', 0))
    room_code = data.get('room', 'A101')
    demo_mode = data.get('demoMode', True)
    demo_seat = data.get('demoSeat', 'B1-D1L')
    ok, msg = processor.start_webcam(cam_id, room_code, demo_mode, demo_seat)
    now_ms = int(time.time() * 1000)
    last_cmd_timestamp = now_ms

    def _sync_cloud():
        try:
            requests.post(
                f'{BACKEND_BASE_URL}/api/device/camera-command',
                json={'active': True, 'roomCode': room_code, 'demoMode': demo_mode, 'demoSeat': demo_seat, 'timestamp': now_ms},
                headers={'X-Device-Key': DEVICE_KEY, 'Content-Type': 'application/json'},
                timeout=2.0
            )
        except Exception:
            pass
    threading.Thread(target=_sync_cloud, daemon=True).start()

    return jsonify({
        'success': ok,
        'message': msg,
        'mode': processor.mode,
        'demoMode': processor.demo_mode,
        'demoSeat': processor.demo_seat_code
    })


@app.route('/api/webcam/stop', methods=['POST'])
def stop_feed():
    global last_cmd_timestamp
    processor.stop()
    now_ms = int(time.time() * 1000)
    last_cmd_timestamp = now_ms

    def _sync_cloud():
        try:
            requests.post(
                f'{BACKEND_BASE_URL}/api/device/camera-command',
                json={'active': False, 'roomCode': processor.room_code, 'timestamp': now_ms},
                headers={'X-Device-Key': DEVICE_KEY, 'Content-Type': 'application/json'},
                timeout=2.0
            )
        except Exception:
            pass
    threading.Thread(target=_sync_cloud, daemon=True).start()

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
        'coordinator': COORDINATOR_STATE
    })

@app.route('/api/coordinator/status', methods=['GET'])
def get_coordinator_status():
    return jsonify(COORDINATOR_STATE)


def process_single_frame(frame, room_code='A101', demo_mode=True, demo_seat='B1-D1L'):
    """Ham phan tich frame doc lap phuc vu Camera tren thiet bi (Dien thoai / Web client - Huong 1)"""
    h, w, _ = frame.shape
    stats = {0: 0, 1: 0, 2: 0, 3: 0, 4: 0, 5: 0}
    total = 0
    detected_phones = []
    has_phone = False
    boxes_data = []

    # 1. Phat hien dien thoai
    if obj_model is not None:
        obj_res = obj_model.predict(frame, classes=[67], conf=0.48, imgsz=416, verbose=False)
        if len(obj_res) > 0 and obj_res[0].boxes is not None:
            for b in obj_res[0].boxes:
                box = b.xyxy[0].cpu().numpy().astype(int)
                conf = float(b.conf[0].item())
                bw = box[2] - box[0]
                bh = box[3] - box[1]
                aspect_ratio = max(bw, bh) / max(1, min(bw, bh))
                if bw >= 28 and bh >= 28 and (1.25 <= aspect_ratio <= 2.8):
                    detected_phones.append({'box': [int(box[0]), int(box[1]), int(box[2]), int(box[3])], 'conf': round(conf, 2)})
                    has_phone = True

    # 2. Phat hien hanh vi
    behav_boxes = []
    if behavior_model is not None:
        b_res = behavior_model.predict(frame, conf=0.25, imgsz=416, verbose=False)
        if len(b_res) > 0 and b_res[0].boxes is not None:
            for b in b_res[0].boxes:
                cid = int(b.cls[0].item())
                box = b.xyxy[0].cpu().numpy().astype(int)
                conf = float(b.conf[0].item())
                behav_boxes.append((cid, box, conf))

    # 3. Xu ly hanh vi
    for (cid, box, conf) in behav_boxes:
        if cid in BEHAVIOR_CLASSES:
            total += 1
            px1, py1, px2, py2 = box
            cx = (px1 + px2) / 2.0
            cy = (py1 + py2) / 2.0
            norm_x = cx / max(1, w)
            norm_y = cy / max(1, h)

            student_has_phone = False
            for ph in detected_phones:
                ph_box = ph['box']
                ph_cx = (ph_box[0] + ph_box[2]) / 2.0
                ph_cy = (ph_box[1] + ph_box[3]) / 2.0
                if (px1 - 60 <= ph_cx <= px2 + 60) and (py1 - 40 <= ph_cy <= py2 + 80):
                    student_has_phone = True
                    break

            if cid == 3:
                act_id = 3 if (student_has_phone or conf >= 0.65) else 2
            else:
                act_id = 3 if student_has_phone else cid

            meta = BEHAVIOR_CLASSES[act_id]
            action_name = meta['name_vi']
            status_key = meta['status']
            col = meta['color']

            stats[act_id] = stats.get(act_id, 0) + 1
            target_seat = demo_seat if demo_mode else map_coord_to_seat(norm_x, norm_y)
            label = f'[DEMO] {target_seat}: {action_name}' if demo_mode else f'{target_seat}: {action_name}'

            boxes_data.append({
                'box': [int(px1), int(py1), int(px2), int(py2)],
                'label': label,
                'color': [int(col[2]), int(col[1]), int(col[0])],
                'status': status_key,
                'action': action_name,
                'seatCode': target_seat
            })

    # 4. Pose model
    if total == 0 and pose_model is not None:
        p_res = pose_model.predict(frame, conf=0.28, imgsz=416, verbose=False)
        if len(p_res) > 0 and p_res[0].boxes is not None and p_res[0].keypoints is not None:
            p_boxes = p_res[0].boxes.xyxy.cpu().numpy().astype(int)
            kpts_data = p_res[0].keypoints.data.cpu().numpy()

            for i, p_box in enumerate(p_boxes):
                total += 1
                kpts = kpts_data[i]
                px1, py1, px2, py2 = p_box
                cx = (px1 + px2) / 2.0
                cy = (py1 + py2) / 2.0
                norm_x = cx / max(1, w)
                norm_y = cy / max(1, h)

                student_has_phone = False
                for ph in detected_phones:
                    ph_box = ph['box']
                    ph_cx = (ph_box[0] + ph_box[2]) / 2.0
                    ph_cy = (ph_box[1] + ph_box[3]) / 2.0
                    if (px1 - 60 <= ph_cx <= px2 + 60) and (py1 - 40 <= ph_cy <= py2 + 80):
                        student_has_phone = True
                        break

                if student_has_phone:
                    action_id = 3
                    action_name = 'Dung dien thoai'
                    status_key = 'using_phone'
                    col = (40, 40, 235)
                else:
                    l_shoulder, r_shoulder = kpts[5], kpts[6]
                    l_wrist, r_wrist = kpts[9], kpts[10]
                    is_raising = False
                    if l_wrist[2] > 0.20 and l_shoulder[2] > 0.20 and l_wrist[1] < l_shoulder[1]:
                        is_raising = True
                    if r_wrist[2] > 0.20 and r_shoulder[2] > 0.20 and r_wrist[1] < r_shoulder[1]:
                        is_raising = True

                    if is_raising:
                        action_id = 0
                        action_name = 'Gio tay'
                        status_key = 'raising_hand'
                        col = (46, 204, 113)
                    else:
                        action_id = 2
                        action_name = 'Hoc bai'
                        status_key = 'studying'
                        col = (155, 89, 182)

                stats[action_id] = stats.get(action_id, 0) + 1
                target_seat = demo_seat if demo_mode else map_coord_to_seat(norm_x, norm_y)
                label = f'[DEMO] {target_seat}: {action_name}' if demo_mode else f'{target_seat}: {action_name}'

                boxes_data.append({
                    'box': [int(px1), int(py1), int(px2), int(py2)],
                    'label': label,
                    'color': [int(col[2]), int(col[1]), int(col[0])],
                    'status': status_key,
                    'action': action_name,
                    'seatCode': target_seat
                })

    # 5. Chi gio dien thoai truoc camera
    if total == 0 and has_phone and demo_mode:
        total = 1
        stats[3] = 1
        boxes_data.append({
            'box': detected_phones[0]['box'],
            'label': f'[DEMO] {demo_seat}: Dung dien thoai',
            'color': [235, 40, 40],
            'status': 'using_phone',
            'action': 'Dung dien thoai',
            'seatCode': demo_seat
        })

    pos = stats.get(0, 0) + stats.get(1, 0) + stats.get(2, 0)
    score = round((pos / max(1, total)) * 100, 1) if total > 0 else 100.0

    primary_action = boxes_data[0]['action'] if boxes_data else 'Trong'
    primary_status = boxes_data[0]['status'] if boxes_data else 'empty'
    primary_seat = boxes_data[0]['seatCode'] if boxes_data else demo_seat

    return {
        'success': True,
        'totalStudents': total,
        'attentionScore': score,
        'stats': {str(k): v for k, v in stats.items()},
        'boxes': boxes_data,
        'phones': detected_phones,
        'phoneAlert': (stats.get(3, 0) > 0 or has_phone),
        'action': primary_action,
        'status': primary_status,
        'seatCode': primary_seat,
        'roomCode': room_code
    }


@app.route('/api/analyze_frame', methods=['POST'])
def analyze_frame_route():
    file = request.files.get('frame')
    if not file:
        return jsonify({'success': False, 'message': 'Chua co frame'}), 400
    nparr = np.frombuffer(file.read(), np.uint8)
    frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    if frame is None:
        return jsonify({'success': False, 'message': 'Loi doc frame'}), 400
    room = request.form.get('room', 'A101')
    seat = request.form.get('seat', 'B1-D1L')
    demo = request.form.get('demoMode', 'true').lower() == 'true'
    res = process_single_frame(frame, room, demo, seat)
    return jsonify(res)


def _client_frame_worker():
    """Luong AI doc frame truc tiep tu Cloud duoc gui tu Camera Dien thoai / Web client"""
    client_session = requests.Session()
    print('[INFO] Da kich hoat Luong AI ho tro Camera Dien thoai (Huong 1)!')
    while True:
        try:
            time.sleep(0.20)
            res = client_session.get(f'{BACKEND_BASE_URL}/api/device/client-frame', timeout=1.8)
            if res.status_code == 200 and len(res.content) > 1000:
                nparr = np.frombuffer(res.content, np.uint8)
                frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
                if frame is not None:
                    analysis = process_single_frame(frame, 'A101', True, 'B1-D1L')
                    client_session.post(
                        f'{BACKEND_BASE_URL}/api/device/client-analysis',
                        json=analysis,
                        headers={'X-Device-Key': DEVICE_KEY, 'Content-Type': 'application/json'},
                        timeout=1.8
                    )
        except Exception:
            pass


last_cmd_timestamp = 0



# ==============================================================================
# IOT CAP DO 5 (IOT LEVEL 5): CLASSROOM COORDINATOR NODE
# - Ket noi truc tiep ESP32 (End Node) qua cap USB Serial (115200 baud).
# - Thu thap du lieu cam bien moi truong, gom chung voi AI Camera thanh 1 goi.
# - Lam dai dien duy nhat cua phong hoc gui du lieu len Cloud Render.
# - Nhan lenh dieu khien tu Cloud va truyen xuong ESP32.
# - Co co che Offline Fallback: tu dong bat quat neu phong nong khi mat mang.
# ==============================================================================
COORDINATOR_STATE = {
    'level': 'IoT Level 5',
    'enabled': True,
    'serial_connected': False,
    'serial_port': None,
    'baud_rate': BAUD_RATE_ARG,
    'last_telemetry': {},
    'last_commands': {'light': False, 'fan': False, 'ac': False},
    'last_update_ts': 0,
    'offline_fallback': False
}

def _serial_coordinator_worker(room_code, preferred_port=None, baud=115200):
    if not HAS_SERIAL:
        print('[COORDINATOR - IOT LEVEL 5] Luu y: Chua cai pyserial. Chay: pip install pyserial')
        return
    print(f'[COORDINATOR - IOT LEVEL 5] Da khoi dong Node Dieu Phoi Serial cho phong [{room_code}] (Baud: {baud})')
    
    while True:
        try:
            ports = [p.device for p in serial.tools.list_ports.comports()]
            target_port = None
            if preferred_port and preferred_port in ports:
                target_port = preferred_port
            elif ports:
                target_port = ports[0]

            if not target_port:
                COORDINATOR_STATE['serial_connected'] = False
                COORDINATOR_STATE['serial_port'] = None
                time.sleep(2.5)
                continue

            print(f'[COORDINATOR] Tim thay cong [{target_port}]. Dang ket noi ESP32...')
            ser = serial.Serial(target_port, baud, timeout=1.5)
            time.sleep(1.0)
            ser.reset_input_buffer()

            COORDINATOR_STATE['serial_connected'] = True
            COORDINATOR_STATE['serial_port'] = target_port
            print(f'>>> [COORDINATOR - IOT LEVEL 5] KET NOI THANH CONG ESP32 TAI CONG [{target_port}]! <<<')

            while True:
                line = ser.readline().decode('utf-8', errors='ignore').strip()
                if not line:
                    continue
                if not (line.startswith('{') and line.endswith('}')):
                    continue
                try:
                    data = json.loads(line)
                    if 'temperature' in data or 'roomCode' in data:
                        COORDINATOR_STATE['last_telemetry'] = data
                        COORDINATOR_STATE['last_update_ts'] = int(time.time() * 1000)

                        payload = {
                            'roomCode': room_code,
                            'temperature': float(data.get('temperature', 26.0)),
                            'humidity': float(data.get('humidity', 60.0)),
                            'lux': float(data.get('lux', 400.0)),
                            'motion': bool(data.get('motion', False))
                        }

                        # 1. Gui telemetry phong hoc len Cloud Render
                        try:
                            r = requests.post(
                                f'{BACKEND_BASE_URL}/api/device/telemetry',
                                json=payload,
                                headers={'X-Device-Key': DEVICE_KEY, 'Content-Type': 'application/json'},
                                timeout=2.0
                            )
                            if r.status_code == 200:
                                cmd = r.json()
                                COORDINATOR_STATE['last_commands'] = cmd
                                COORDINATOR_STATE['offline_fallback'] = False

                                # Gui lenh dieu khien nguoc lai xuong ESP32 qua Serial
                                cmd_line = json.dumps({
                                    'light': bool(cmd.get('light', False)),
                                    'fan': bool(cmd.get('fan', False)),
                                    'ac': bool(cmd.get('ac', False))
                                }) + '\n'
                                ser.write(cmd_line.encode('utf-8'))
                        except Exception:
                            # 2. Local Fallback: Tu dong dieu khien cuc bo neu mat mang Internet
                            COORDINATOR_STATE['offline_fallback'] = True
                            temp = payload['temperature']
                            motion = payload['motion']
                            local_cmd = {
                                'light': motion,
                                'fan': (temp >= 28.0 and motion),
                                'ac': (temp >= 30.0 and motion)
                            }
                            COORDINATOR_STATE['last_commands'] = local_cmd
                            cmd_line = json.dumps(local_cmd) + '\n'
                            ser.write(cmd_line.encode('utf-8'))
                except Exception:
                    pass

        except Exception as e:
            COORDINATOR_STATE['serial_connected'] = False
            COORDINATOR_STATE['serial_port'] = None
            time.sleep(2.0)

def _cloud_listener():
    """Lang nghe lenh Bat/Tat Webcam truc tiep tu Web Dashboard va gui Heartbeat theo phong"""
    global last_cmd_timestamp
    print(f'[INFO] AI Server da ket noi Cloud: {BACKEND_BASE_URL}')
    print(f'[INFO] Phu trach phong hoc: [{processor.room_code}]')
    print(f'[INFO] Che do: CHO LENH TU WEB (Webcam chi bat khi co lenh bat cam phong [{processor.room_code}])!')

    # Lay timestamp lenh hien tai cua phong tren Cloud de bo qua cac thao tac cu
    try:
        r0 = requests.get(f'{BACKEND_BASE_URL}/api/device/camera-command?room={processor.room_code}', timeout=2.5)
        if r0.status_code == 200:
            last_cmd_timestamp = r0.json().get('timestamp', 0)
    except Exception:
        last_cmd_timestamp = int(time.time() * 1000)

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
                cmd_room = cmd.get('roomCode', '').strip().upper()
                cmd_ts = cmd.get('timestamp', 0)
                cmd_active = cmd.get('active', False)

                # CHI XU LY KHI CO LENH MOI VA DUNG CHO PHONG MAY NAY PHU TRACH!
                if cmd_room == processor.room_code and cmd_ts > last_cmd_timestamp:
                    last_cmd_timestamp = cmd_ts
                    if cmd_active and not processor.is_running:
                        demo_mode = cmd.get('demoMode', True)
                        demo_seat = cmd.get('demoSeat', 'B1-D1L')
                        print(f'\n>>> [WEB] NHAN LENH BAT WEBCAM CHO PHONG [{processor.room_code}] (Cho: {demo_seat}) <<<')
                        processor.start_webcam(DEFAULT_CAM_ID, processor.room_code, demo_mode, demo_seat)
                    elif not cmd_active and processor.is_running:
                        print(f'\n>>> [WEB] NHAN LENH TAT WEBCAM CHO PHONG [{processor.room_code}] <<<')
                        processor.stop()
        except Exception:
            pass


if __name__ == '__main__':
    print('=' * 65)
    print('   SMART CLASSROOM - MAY TRAM CAMERA AI THEO PHONG')
    print(f'   * PHONG HOC PHU TRACH: [{processor.room_code}]')
    print(f'   * CHE DO: CHO LENH BAT WEBCAM CHO PHONG [{processor.room_code}]')
    print('   * Nhan dien: Gio tay, Dung dien thoai, Do tap trung')
    print(f'   * Cloud Server: {BACKEND_BASE_URL}')
    print(f'   * Video stream:  http://localhost:5000/video_feed')
    print(f'   * Status API:    http://localhost:5000/api/status')
    print('=================================================================')
    print(f'[INFO] San sang! Khi giao dien Web xem phong [{processor.room_code}] va bat cam,')
    print('       may nay se tu dong bat webcam de truyen hinh anh len he thong.')
    print('=================================================================')

    # 1. Khoi dong Nút Dieu Phoi Serial (IoT Cap do 5)
    threading.Thread(
        target=_serial_coordinator_worker,
        args=(processor.room_code, SERIAL_PORT_ARG, BAUD_RATE_ARG),
        daemon=True
    ).start()

    # 2. Khoi dong thread lang nghe Cloud
    threading.Thread(target=_cloud_listener, daemon=True).start()

    # 2. Chay Flask Server
    app.run(host='0.0.0.0', port=5000, debug=False, threaded=True)
