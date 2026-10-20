// System Global Variables
    let currentRoomId = null;
    let currentRoomCode = 'A101';
    let currentUser = { username: '', fullName: '', role: 'TEACHER' };
    let envChart = null;
    let behaviorChart = null;
    let isCameraRunning = false;
    let camInterval = null;

    const DAYS_TEXT = {
      1: 'Thứ Hai', 2: 'Thứ Ba', 3: 'Thứ Tư', 4: 'Thứ Năm',
      5: 'Thứ Sáu', 6: 'Thứ Bảy', 7: 'Chủ Nhật'
    };

    // Helper: DOM selector
    const $ = id => document.getElementById(id);

    // Toast Notification
    function showToast(msg, duration = 3000) {
      const t = $('toast');
      t.textContent = msg;
      t.classList.add('show');
      setTimeout(() => t.classList.remove('show'), duration);
    }

    // Modal helpers
    function openModal(id) { $(id).style.display = 'flex'; }
    function closeModal(id) { $(id).style.display = 'none'; }
    document.querySelectorAll('.modal-backdrop').forEach(m => {
      m.addEventListener('click', e => { if (e.target === m) closeModal(m.id); });
    });
    document.addEventListener('keydown', e => {
      if (e.key === 'Escape') {
        document.querySelectorAll('.modal-backdrop').forEach(m => closeModal(m.id));
      }
    });

    // API fetch wrapper
    async function fetchApi(url, opts = {}) {
      opts.headers = { 'Content-Type': 'application/json', ...(opts.headers || {}) };
      if (opts.body && typeof opts.body === 'object' && !(opts.body instanceof FormData)) {
        opts.body = JSON.stringify(opts.body);
      }
      const res = await fetch(url, opts);
      if (res.status === 401) {
        window.location.href = '/login.html';
        return null;
      }
      if (!res.ok) {
        let msg = 'Lỗi yêu cầu: ' + res.status;
        try {
          const err = await res.json();
          if (err.message) msg = err.message;
        } catch (_) {}
        throw new Error(msg);
      }
      const cType = res.headers.get('content-type');
      if (cType && cType.includes('application/json')) return res.json();
      return res.text();
    }

    // Live Clock
    setInterval(() => {
      $('liveClock').textContent = new Date().toLocaleTimeString('vi-VN');
    }, 1000);

    // Tab switching in Sidebar
    document.querySelectorAll('.nav-tab-btn').forEach(btn => {
      btn.onclick = () => {
        document.querySelectorAll('.nav-tab-btn').forEach(b => b.classList.remove('on'));
        document.querySelectorAll('.tab').forEach(t => t.classList.remove('on'));
        btn.classList.add('on');
        const targetTab = $(btn.dataset.tab);
        if (targetTab) targetTab.classList.add('on');

        if (btn.dataset.tab === 'tab-camera-seating') {
          loadBehaviorData();
          loadBehaviorChart();
          checkCameraStatus();
          loadSeats();
        }
        if (btn.dataset.tab === 'tab-students') {
          loadStudents();
          loadStudentSummary();
          loadStudentClassFilter();
        }
        if (btn.dataset.tab === 'tab-dashboard') { loadEnvStatus(); loadEnvChart(); }
        if (btn.dataset.tab === 'tab-schedule') { loadSchedules(); }
        if (btn.dataset.tab === 'tab-admin-users') { loadUsers(); }
        if (btn.dataset.tab === 'tab-admin-rooms') { loadRoomsAdmin(); }
        if (btn.dataset.tab === 'tab-logs') { loadLogs(); }
      };
    });

    // ================= 1. REFRESH STATUS & DEVICE CONTROLS =================
    async function loadEnvStatus() {
      if (!currentRoomId) return;
      try {
        const data = await fetchApi('/api/rooms/' + currentRoomId + '/status');
        if (!data) return;

        // Sensor values
        if (data.sensor) {
          $('valTemp').textContent = data.sensor.temperature != null ? data.sensor.temperature.toFixed(1) : '--';
          $('valHum').textContent = data.sensor.humidity != null ? data.sensor.humidity.toFixed(0) : '--';
          $('valLux').textContent = data.sensor.lux != null ? data.sensor.lux.toFixed(0) : '--';
          $('valMotion').textContent = data.sensor.motion ? 'Có người' : 'Phòng trống';
          $('sensorUpdatedTime').textContent = 'Cập nhật lúc: ' + new Date(data.sensor.createdAt).toLocaleTimeString('vi-VN');
        }

        // Device switches
        if (data.device) {
          $('swLight').checked = !!data.device.light;
          $('swFan').checked = !!data.device.fan;
          $('swAc').checked = !!data.device.ac;
          $('swAuto').checked = !!data.device.auto;
          $('acTempText').textContent = data.device.acTemp || 25;

          data.device.light ? $('devLightCard').classList.add('active') : $('devLightCard').classList.remove('active');
          data.device.fan ? $('devFanCard').classList.add('active') : $('devFanCard').classList.remove('active');
          data.device.ac ? $('devAcCard').classList.add('active') : $('devAcCard').classList.remove('active');
        }
      } catch (e) {
        console.error('Error loading env status:', e);
      }
    }

    async function sendControl(device, state, acTemp = null) {
      if (!currentRoomId) return;
      const body = { device, state };
      if (acTemp !== null) body.acTemp = acTemp;
      try {
        await fetchApi('/api/rooms/' + currentRoomId + '/control', {
          method: 'POST',
          body
        });
        showToast('Đã gửi lệnh điều khiển ' + device);
        loadEnvStatus();
      } catch (e) {
        showToast(e.message);
      }
    }

    $('swLight').onchange = e => sendControl('light', e.target.checked);
    $('swFan').onchange = e => sendControl('fan', e.target.checked);
    $('swAc').onchange = e => sendControl('ac', e.target.checked);
    $('swAuto').onchange = e => sendControl('auto', e.target.checked);

    $('btnAcDown').onclick = () => {
      let t = +$('acTempText').textContent;
      if (t > 16) {
        t--;
        $('acTempText').textContent = t;
        sendControl('ac', $('swAc').checked, t);
      }
    };
    $('btnAcUp').onclick = () => {
      let t = +$('acTempText').textContent;
      if (t < 30) {
        t++;
        $('acTempText').textContent = t;
        sendControl('ac', $('swAc').checked, t);
      }
    };

    async function loadEnvChart() {
      if (!currentRoomId) return;
      try {
        const hrs = $('chartHours').value || 1;
        const list = await fetchApi('/api/rooms/' + currentRoomId + '/sensors/history?hours=' + hrs);
        if (!list) return;

        const labels = list.map(s => new Date(s.createdAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }));
        const chartData = {
          labels,
          datasets: [
            {
              label: 'Nhiệt độ (°C)',
              data: list.map(s => s.temperature),
              borderColor: '#0284c7',
              backgroundColor: 'rgba(2, 132, 199, 0.08)',
              yAxisID: 'yTemp',
              tension: 0.3
            },
            {
              label: 'Ánh sáng (lux)',
              data: list.map(s => s.lux),
              borderColor: '#9a5b07',
              backgroundColor: 'rgba(154, 91, 7, 0.08)',
              yAxisID: 'yLux',
              tension: 0.3
            }
          ]
        };

        if (envChart) {
          envChart.data = chartData;
          envChart.update('none');
          return;
        }

        const ctx = $('envChart');
        if (!ctx) return;
        envChart = new Chart(ctx, {
          type: 'line',
          data: chartData,
          options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            scales: {
              yTemp: {
                position: 'left',
                title: { display: true, text: 'Nhiệt độ (°C)' }
              },
              yLux: {
                position: 'right',
                grid: { drawOnChartArea: false },
                title: { display: true, text: 'Ánh sáng (lux)' }
              }
            }
          }
        });
      } catch (e) {
        console.error('Error loading env chart:', e);
      }
    }
    $('chartHours').onchange = loadEnvChart;

    // ================= 2. CAMERA AI & SEATING MAP (THEO PHONG HOC) =================
    let frameLoopActive = false;
    let frameTimer = null;

    function startFrameLoop() {
      if (frameLoopActive) return;
      frameLoopActive = true;

      function loadNextFrame() {
        if (!isCameraRunning) {
          frameLoopActive = false;
          if (frameTimer) clearTimeout(frameTimer);
          return;
        }

        const room = currentRoomCode || 'A101';
        const img = $('aiStreamImg');
        const nextImg = new Image();
        nextImg.onload = () => {
          if (isCameraRunning && (currentRoomCode || 'A101') === room) {
            img.src = nextImg.src;
            frameTimer = setTimeout(loadNextFrame, 15);
          } else {
            frameLoopActive = false;
          }
        };
        nextImg.onerror = () => {
          if (isCameraRunning && (currentRoomCode || 'A101') === room) {
            frameTimer = setTimeout(loadNextFrame, 120);
          } else {
            frameLoopActive = false;
          }
        };
        nextImg.src = '/api/device/camera-frame?room=' + encodeURIComponent(room) + '&t=' + Date.now();
      }

      loadNextFrame();
    }

    async function checkCameraStatus() {
      try {
        const room = currentRoomCode || 'A101';
        let localStatus = null;
        if (window.location.protocol === 'http:' && (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1')) {
          try {
            const lRes = await fetch('http://localhost:5000/api/status', { signal: AbortSignal.timeout(600) });
            if (lRes.ok) {
              const ls = await lRes.json();
              if (ls.room === room) localStatus = ls;
            }
          } catch (_) {}
        }

        const res = await fetchApi('/api/device/camera-status?room=' + encodeURIComponent(room)).catch(() => null);

        let running = false;
        let online = false;
        let info = {};
        if (localStatus && localStatus.running) {
          running = true;
          online = true;
          info = localStatus;
        } else if (res) {
          online = !!res.online;
          running = !!(res.running || res.isRunning);
          info = res;
        }

        isCameraRunning = running;
        updateCameraUi(running, online, info);
      } catch (_) {}
    }

    function updateCameraUi(running, online, info = {}) {
      isCameraRunning = running;
      const placeholder = $('streamPlaceholder');
      const placeholderTitle = $('camPlaceholderTitle');
      const placeholderDesc = $('camPlaceholderDesc');
      const img = $('aiStreamImg');
      const badge = $('aiStreamStatusBadge');
      const room = currentRoomCode || 'A101';

      if (running) {
        placeholder.style.display = 'none';
        img.style.display = 'block';
        badge.textContent = `Trực tiếp (${room})`;
        badge.className = 'connection-chip danger';

        if ((window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') && (!info.room || info.room === room)) {
          if (!img.src || !img.src.includes(':5000')) {
            img.src = 'http://localhost:5000/video_feed';
          }
        } else {
          startFrameLoop();
        }
      } else {
        frameLoopActive = false;
        if (frameTimer) { clearTimeout(frameTimer); frameTimer = null; }
        placeholder.style.display = 'block';
        img.style.display = 'none';
        img.src = '';

        if (!online) {
          badge.textContent = 'Chưa kết nối AI';
          badge.className = 'connection-chip warning';
          if (placeholderTitle) placeholderTitle.textContent = `Máy trạm AI phòng ${room} chưa kết nối`;
          if (placeholderDesc) placeholderDesc.textContent = `Vui lòng khởi động run_ai.bat cho phòng ${room} trên máy tính phụ trách phòng này.`;
        } else {
          badge.textContent = `Sẵn sàng (${room})`;
          badge.className = 'connection-chip online';
          if (placeholderTitle) placeholderTitle.textContent = `Camera AI phòng ${room} đang tắt`;
          if (placeholderDesc) placeholderDesc.textContent = 'Nhấn nút "Bật Camera AI" bên dưới để kích hoạt camera phòng học.';
        }
      }

      if (info.fps != null) $('camFpsText').textContent = typeof info.fps === 'number' ? info.fps.toFixed(1) : info.fps;
      if (info.totalStudents != null) $('camPersonCount').textContent = info.totalStudents;
      if (info.attentionScore != null) $('aiScoreNumber').textContent = (typeof info.attentionScore === 'number' ? info.attentionScore.toFixed(0) : info.attentionScore) + '%';

      const chipCoordinator = $('chipCoordinator');
      if (chipCoordinator && info.coordinator) {
        if (info.coordinator.serial_connected) {
          chipCoordinator.textContent = `Node Điều phối: Kết nối USB (${info.coordinator.serial_port || 'COM'})`;
          chipCoordinator.style.background = '#e6f4ea';
          chipCoordinator.style.color = 'var(--success)';
          chipCoordinator.style.borderColor = '#b7e1cd';
        } else {
          chipCoordinator.textContent = 'Node Điều phối: Cấp độ 5 (Chờ USB)';
          chipCoordinator.style.background = 'var(--accent-soft)';
          chipCoordinator.style.color = 'var(--accent)';
          chipCoordinator.style.borderColor = '#bae6fd';
        }
      }
    }

    $('btnStartCam').onclick = async () => {
      const room = currentRoomCode || 'A101';
      const isDemo = $('camModeSelect') ? ($('camModeSelect').value === 'demo') : true;
      const modeLabel = isDemo ? 'Webcam Demo (1 ghế B1-D1L)' : 'Camera phòng học (Toàn lớp)';
      try {
        showToast(`Đang kích hoạt camera phòng ${room} [${modeLabel}]...`);
        if (window.location.protocol === 'http:' && (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1')) {
          fetch('http://localhost:5000/api/webcam/start', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ room: room, demoMode: isDemo, demoSeat: 'B1-D1L' })
          }).catch(() => {});
        }
        await fetchApi(`/api/device/camera-command?cmd=start_cam&room=${encodeURIComponent(room)}&demoMode=${isDemo}`).catch(() => {});
        await fetchApi('/api/device/camera-command', {
          method: 'POST',
          body: { active: true, roomCode: room, demoMode: isDemo, demoSeat: 'B1-D1L' }
        }).catch(() => {});

        showToast(`Đã bật Camera phòng ${room} [${modeLabel}]!`);
        updateCameraUi(true, true, { room: room });
        setTimeout(checkCameraStatus, 800);
      } catch (e) {
        showToast(e.message);
      }
    };

    $('btnStopCam').onclick = async () => {
      const room = currentRoomCode || 'A101';
      try {
        if (window.location.protocol === 'http:' && (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1')) {
          fetch('http://localhost:5000/api/webcam/stop', { method: 'POST' }).catch(() => {});
        }
        await fetchApi('/api/device/camera-command?cmd=stop&room=' + encodeURIComponent(room)).catch(() => {});
        await fetchApi('/api/device/camera-command', {
          method: 'POST',
          body: { active: false, roomCode: room }
        }).catch(() => {});
        showToast(`Đã tắt Camera AI phòng ${room}`);
        updateCameraUi(false, true, { room: room });
      } catch (e) {
        showToast(e.message);
      }
    };

    const btnFullscreenCam = $('btnFullscreenCam');
    if (btnFullscreenCam) {
      btnFullscreenCam.onclick = () => {
        const sc = $('streamContainer');
        if (!document.fullscreenElement && !document.webkitFullscreenElement) {
          if (sc.requestFullscreen) {
            sc.requestFullscreen().catch(() => {});
          } else if (sc.webkitRequestFullscreen) {
            sc.webkitRequestFullscreen();
          }
        } else {
          if (document.exitFullscreen) {
            document.exitFullscreen().catch(() => {});
          } else if (document.webkitExitFullscreen) {
            document.webkitExitFullscreen();
          }
        }
      };
    }

    async function loadBehaviorData() {
      if (!currentRoomId) return;
      try {
        const beh = await fetchApi('/api/rooms/' + currentRoomId + '/behavior/latest');
        if (!beh) return;

        $('statRaising').textContent = beh.raisingHand || 0;
        $('statStudying').textContent = (beh.readingBook || 0) + (beh.writing || 0) + (beh.listening || 0);
        $('statPhone').textContent = beh.usingPhone || 0;
        $('statSleeping').textContent = (beh.bowingHead || 0) + (beh.lyingDesk || 0);

        // Check for phone alert
        if (beh.usingPhone > 0) {
          $('instantAlertMsg').textContent = 'Cảnh báo: Phát hiện có ' + beh.usingPhone + ' học sinh đang sử dụng điện thoại!';
          $('instantAlertBox').style.display = 'flex';
        }
      } catch (e) {
        console.error('Error loading behavior data:', e);
      }
    }

    async function loadBehaviorChart() {
      if (!currentRoomId) return;
      try {
        const hrs = $('aiChartHours').value || 1;
        const list = await fetchApi('/api/rooms/' + currentRoomId + '/behavior/history?hours=' + hrs);
        if (!list) return;

        const labels = list.map(b => new Date(b.createdAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }));
        const chartData = {
          labels,
          datasets: [
            {
              label: 'Giơ tay phát biểu',
              data: list.map(b => b.raisingHand),
              borderColor: '#2457e6',
              backgroundColor: 'rgba(36, 87, 230, 0.08)',
              tension: 0.3
            },
            {
              label: 'Dùng điện thoại',
              data: list.map(b => b.usingPhone),
              borderColor: '#b93a3a',
              backgroundColor: 'rgba(185, 58, 58, 0.08)',
              tension: 0.3
            },
            {
              label: 'Tập trung học',
              data: list.map(b => (b.readingBook || 0) + (b.writing || 0)),
              borderColor: '#2c7a4f',
              backgroundColor: 'rgba(44, 122, 79, 0.08)',
              tension: 0.3
            }
          ]
        };

        if (behaviorChart) {
          behaviorChart.data = chartData;
          behaviorChart.update('none');
          return;
        }

        const ctx = $('behaviorChart');
        if (!ctx) return;
        behaviorChart = new Chart(ctx, {
          type: 'line',
          data: chartData,
          options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false }
          }
        });
      } catch (e) {
        console.error('Error loading behavior chart:', e);
      }
    }
    $('aiChartHours').onchange = loadBehaviorChart;

    // Seating Map
    let allCurrentSeats = [];

    async function loadSeats() {
      if (!currentRoomId) return;
      try {
        const layout = await fetchApi('/api/rooms/' + currentRoomId + '/layout');
        const seats = await fetchApi('/api/rooms/' + currentRoomId + '/seats');
        if (!seats) return;
        allCurrentSeats = seats;

        const colCount = layout.colCount || 4;
        const rowCount = layout.rowCount || 5;
        const perDesk = layout.seatsPerDesk || 2;

        document.documentElement.style.setProperty('--desk-cols', colCount);

        // Group seats by Desk
        const desksMap = {};
        for (let r = 1; r <= rowCount; r++) {
          for (let c = 1; c <= colCount; c++) {
            const deskKey = `B${r}-D${c}`;
            desksMap[deskKey] = [];
          }
        }

        seats.forEach(s => {
          const r = s.rowNum || s.seatRow || 1;
          const c = s.colNum || s.seatCol || 1;
          const deskKey = `B${r}-D${c}`;
          if (!desksMap[deskKey]) desksMap[deskKey] = [];
          desksMap[deskKey].push(s);
        });

        let html = '';
        Object.keys(desksMap).sort().forEach(deskKey => {
          const deskSeats = desksMap[deskKey];
          html += `
            <div class="desk-unit">
              <div class="desk-label">Bàn ${deskKey}</div>
              <div class="desk-seats-row ${perDesk === 1 ? 'single' : ''}">
          `;

          deskSeats.forEach(st => {
            let stateClass = 'state-studying';
            let tagText = 'Học bài';

            if (st.currentStatus === 'using_phone') {
              stateClass = 'state-phone';
              tagText = 'Dùng ĐT';
            } else if (st.currentStatus === 'raising_hand') {
              stateClass = 'state-raising';
              tagText = 'Giơ tay';
            } else if (st.currentStatus === 'bowing_head') {
              stateClass = 'state-sleeping';
              tagText = 'Gục đầu';
            } else if (!st.studentName) {
              stateClass = 'state-empty';
              tagText = 'Trống';
            }

            html += `
              <div class="seat-cell ${stateClass}" onclick="openSeatModal('${st.seatCode}')">
                <div class="seat-top">
                  <span class="seat-code">${st.seatCode}</span>
                  <span class="seat-tag ${stateClass}">${tagText}</span>
                </div>
                <div class="seat-student-name" title="${st.studentName || 'Chưa gán học sinh'}">
                  ${st.studentName || '<span style="color:#8a909b; font-style:italic;">Trống</span>'}
                </div>
                <div class="seat-action-text">${st.currentAction || 'Đang học'}</div>
              </div>
            `;
          });

          html += `</div></div>`;
        });

        $('seatingGrid').innerHTML = html;
      } catch (e) {
        console.error('Error loading seating map:', e);
      }
    }
    $('btnRefreshSeats').onclick = loadSeats;

    function openSeatModal(seatCode) {
      const s = allCurrentSeats.find(x => x.seatCode === seatCode);
      if (!s) return;
      const rNum = s.rowNum || s.seatRow || 1;
      const cNum = s.colNum || s.seatCol || 1;
      const sSide = s.side || s.deskSide || '';
      $('modalSeatTitle').textContent = `Chi tiết Ghế ${seatCode} - Bàn ${rNum}, Dãy ${cNum}`;
      $('modalSeatCode').value = s.seatCode;
      $('modalSeatCodeDisplay').value = `${s.seatCode} (${sSide === 'L' ? 'Ghế Trái' : sSide === 'R' ? 'Ghế Phải' : 'Ghế Đơn'})`;
      $('modalSeatStudentCode').value = s.studentCode || '';
      $('modalSeatStudentName').value = s.studentName || '';
      $('modalSeatAction').value = `${s.currentAction || 'Đang học bài'} [Trạng thái: ${s.currentStatus || 'studying'}]`;
      $('modalSeatNotes').value = s.notes || '';
      openModal('modalSeat');
    }

    $('btnSaveSeat').onclick = async () => {
      const seatCode = $('modalSeatCode').value;
      const studentName = $('modalSeatStudentName').value.trim();
      const studentCode = $('modalSeatStudentCode').value.trim();
      const notes = $('modalSeatNotes').value.trim();
      try {
        await fetchApi(`/api/rooms/${currentRoomId}/seats/${seatCode}`, {
          method: 'PUT',
          body: { studentName, studentCode, notes }
        });
        closeModal('modalSeat');
        showToast(`Đã lưu thông tin ghế ${seatCode}`);
        loadSeats();
      } catch (e) {
        showToast(e.message);
      }
    };

    // ================= 3. TIMETABLE / SCHEDULE =================
    async function loadSchedules() {
      if (!currentRoomId) return;
      try {
        const list = await fetchApi('/api/schedules?roomId=' + currentRoomId);
        const todayDay = new Date().getDay() || 7;

        let html = '';
        for (let d = 1; d <= 7; d++) {
          const isToday = d === todayDay;
          const daySlots = (list || []).filter(x => x.dayOfWeek === d);

          html += `
            <div class="day-col ${isToday ? 'today' : ''}">
              <div class="day-title">${DAYS_TEXT[d]} ${isToday ? '(Hôm nay)' : ''}</div>
          `;

          if (daySlots.length === 0) {
            html += `<div style="color: var(--text-subtle); font-size: 11px; text-align: center; margin-top: 20px;">Trống lịch</div>`;
          } else {
            daySlots.forEach(s => {
              html += `
                <div class="slot-item">
                  ${currentUser.role === 'ADMIN' ? `<button class="slot-del" title="Xóa tiết học" onclick="deleteSchedule(${s.id})">×</button>` : ''}
                  <b>${s.subject}</b>
                  <div class="slot-time">${s.startTime.slice(0,5)} - ${s.endTime.slice(0,5)}</div>
                  <div class="slot-teacher">GV: ${s.teacher || 'Chưa phân công'}</div>
                </div>
              `;
            });
          }

          html += `</div>`;
        }

        $('scheduleWeekGrid').innerHTML = html;
      } catch (e) {
        console.error('Error loading schedule:', e);
      }
    }

    $('btnOpenAddSchedule').onclick = () => openModal('modalAddSchedule');

    $('btnSubmitAddSchedule').onclick = async () => {
      const dayOfWeek = +$('schDay').value;
      const startTime = $('schStart').value;
      const endTime = $('schEnd').value;
      const subject = $('schSubject').value.trim();
      const teacher = $('schTeacher').value.trim();

      if (!subject) return showToast('Vui lòng nhập tên môn học!');
      try {
        await fetchApi('/api/schedules', {
          method: 'POST',
          body: { roomId: currentRoomId, dayOfWeek, startTime, endTime, subject, teacher }
        });
        closeModal('modalAddSchedule');
        showToast('Đã thêm tiết học mới!');
        loadSchedules();
      } catch (e) {
        showToast(e.message);
      }
    };

    async function deleteSchedule(id) {
      if (!confirm('Bạn có chắc chắn muốn xóa tiết học này?')) return;
      try {
        await fetchApi('/api/schedules/' + id, { method: 'DELETE' });
        showToast('Đã xóa tiết học');
        loadSchedules();
      } catch (e) {
        showToast(e.message);
      }
    }

    // ================= 4. ADMIN: USER MANAGEMENT =================
    async function loadUsers() {
      if (currentUser.role !== 'ADMIN') return;
      try {
        const users = await fetchApi('/api/users');
        if (!users) return;

        let html = '';
        users.forEach(u => {
          const isSelf = u.username.toLowerCase() === currentUser.username.toLowerCase();
          const isDefaultAdmin = u.username.toLowerCase() === 'admin';

          html += `
            <tr>
              <td>#${u.id}</td>
              <td><b>${u.username}</b></td>
              <td>${u.fullName || '---'}</td>
              <td>
                <span class="role-badge ${u.role === 'ADMIN' ? 'admin' : 'teacher'}">
                  ${u.role === 'ADMIN' ? 'Quản trị viên' : 'Giáo viên'}
                </span>
              </td>
              <td>
                <div style="display: flex; gap: 6px;">
                  <button class="button small" onclick="openEditUser(${u.id}, '${u.username}', '${u.fullName || ''}', '${u.role}')">
                    Sửa
                  </button>
                  ${(!isSelf && !isDefaultAdmin) ? `
                    <button class="button small danger" onclick="deleteUser(${u.id}, '${u.username}')">
                      Xóa
                    </button>
                  ` : ''}
                </div>
              </td>
            </tr>
          `;
        });

        $('userTableBody').innerHTML = html;
      } catch (e) {
        console.error('Error loading users:', e);
      }
    }

    $('btnOpenCreateUser').onclick = () => {
      $('newUsername').value = '';
      $('newPassword').value = '';
      $('newFullName').value = '';
      openModal('modalCreateUser');
    };

    $('btnSubmitCreateUser').onclick = async () => {
      const username = $('newUsername').value.trim();
      const password = $('newPassword').value.trim();
      const fullName = $('newFullName').value.trim();
      const role = $('newRole').value;

      if (!username || !password) return showToast('Vui lòng điền tên đăng nhập và mật khẩu!');
      try {
        await fetchApi('/api/users', {
          method: 'POST',
          body: { username, password, fullName, role }
        });
        closeModal('modalCreateUser');
        showToast(`Đã tạo tài khoản "${username}" thành công!`);
        loadUsers();
      } catch (e) {
        showToast(e.message);
      }
    };

    function openEditUser(id, username, fullName, role) {
      $('editUserId').value = id;
      $('editUsername').value = username;
      $('editFullName').value = fullName;
      $('editRole').value = role;
      $('editNewPassword').value = '';
      openModal('modalEditUser');
    }

    $('btnSubmitEditUser').onclick = async () => {
      const id = $('editUserId').value;
      const fullName = $('editFullName').value.trim();
      const role = $('editRole').value;
      const newPassword = $('editNewPassword').value.trim();

      try {
        await fetchApi('/api/users/' + id, {
          method: 'PUT',
          body: { fullName, role, newPassword }
        });
        closeModal('modalEditUser');
        showToast('Đã cập nhật thông tin tài khoản!');
        loadUsers();
      } catch (e) {
        showToast(e.message);
      }
    };

    async function deleteUser(id, username) {
      if (!confirm(`Bạn có chắc chắn muốn xóa tài khoản "${username}"?`)) return;
      try {
        await fetchApi('/api/users/' + id, { method: 'DELETE' });
        showToast(`Đã xóa tài khoản ${username}`);
        loadUsers();
      } catch (e) {
        showToast(e.message);
      }
    }

    // ================= 5. ADMIN: ROOM MANAGEMENT =================
    async function loadRoomsAdmin() {
      if (currentUser.role !== 'ADMIN') return;
      try {
        const rooms = await fetchApi('/api/rooms');
        if (!rooms) return;

        let html = '';
        rooms.forEach(r => {
          html += `
            <tr>
              <td>#${r.id}</td>
              <td><b>${r.code}</b></td>
              <td>${r.name}</td>
              <td><b>${r.capacity} chỗ</b></td>
              <td>
                <div style="display: flex; gap: 6px;">
                  <button class="button small" onclick="openConfigLayout(${r.id})">
                    Đổi sơ đồ
                  </button>
                  ${rooms.length > 1 ? `
                    <button class="button small danger" onclick="deleteRoom(${r.id}, '${r.code}')">
                      Xóa phòng
                    </button>
                  ` : ''}
                </div>
              </td>
            </tr>
          `;
        });

        $('roomTableBody').innerHTML = html;
      } catch (e) {
        console.error('Error loading rooms admin:', e);
      }
    }

    function openConfigLayout(roomId) {
      openModal('modalConfigLayout');
    }

    $('btnConfigSeatingAdmin').onclick = () => openConfigLayout(currentRoomId);

    $('btnSubmitConfigLayout').onclick = async () => {
      const rowCount = +$('cfgRows').value;
      const colCount = +$('cfgCols').value;
      const seatsPerDesk = +$('cfgPerDesk').value;
      try {
        await fetchApi(`/api/rooms/${currentRoomId}/layout`, {
          method: 'POST',
          body: { rowCount, colCount, seatsPerDesk }
        });
        closeModal('modalConfigLayout');
        showToast('Đã cấu hình lại sơ đồ bàn ghế thành công!');
        loadSeats();
      } catch (e) {
        showToast(e.message);
      }
    };

    $('btnOpenCreateRoom').onclick = () => {
      $('newRoomCode').value = '';
      $('newRoomName').value = '';
      openModal('modalCreateRoom');
    };

    $('btnSubmitCreateRoom').onclick = async () => {
      const code = $('newRoomCode').value.trim();
      const name = $('newRoomName').value.trim();
      const rowCount = +$('newRoomRows').value;
      const colCount = +$('newRoomCols').value;
      const seatsPerDesk = +$('newRoomPerDesk').value;

      if (!code || !name) return showToast('Vui lòng nhập mã phòng và tên phòng!');
      try {
        await fetchApi('/api/rooms', {
          method: 'POST',
          body: { code, name, rowCount, colCount, seatsPerDesk }
        });
        closeModal('modalCreateRoom');
        showToast(`Đã tạo phòng học "${code}" thành công!`);
        await initRooms();
        loadRoomsAdmin();
      } catch (e) {
        showToast(e.message);
      }
    };

    async function deleteRoom(id, code) {
      if (!confirm(`Bạn có chắc chắn muốn xóa phòng học "${code}"?`)) return;
      try {
        await fetchApi('/api/rooms/' + id, { method: 'DELETE' });
        showToast(`Đã xóa phòng học ${code}`);
        await initRooms();
        loadRoomsAdmin();
      } catch (e) {
        showToast(e.message);
      }
    }

    // ================= 6. SYSTEM AUDIT LOGS =================
    async function loadLogs() {
      try {
        const list = await fetchApi('/api/logs?roomId=' + (currentRoomId || ''));
        if (!list) return;

        let html = '';
        if (list.length === 0) {
          html = `<tr><td colspan="3" style="text-align:center; color:var(--text-subtle); padding:32px;">Chưa có nhật ký hoạt động nào</td></tr>`;
        } else {
          list.forEach(l => {
            html += `
              <tr>
                <td style="font-family: var(--mono); font-size:12px; color:var(--text-muted);">${new Date(l.createdAt).toLocaleString('vi-VN')}</td>
                <td><b>${l.actionBy}</b></td>
                <td>${l.description}</td>
              </tr>
            `;
          });
        }

        $('logsTableBody').innerHTML = html;
      } catch (e) {
        console.error('Error loading logs:', e);
      }
    }

    // ================= INITIALIZATION =================
    async function initRooms() {
      const rooms = await fetchApi('/api/rooms');
      if (!rooms || rooms.length === 0) return;

      $('roomSelect').innerHTML = rooms.map(r => `<option value="${r.id}" data-code="${r.code}">${r.name}</option>`).join('');
      currentRoomId = rooms[0].id;
      currentRoomCode = rooms[0].code;
    }

    $('roomSelect').onchange = () => {
      const sel = $('roomSelect');
      currentRoomId = +sel.value;
      currentRoomCode = sel.options[sel.selectedIndex].dataset.code;

      loadEnvStatus();
      loadEnvChart();
      loadSeats();
      loadSchedules();
      loadLogs();
      checkCameraStatus();
      if ($('tab-students') && $('tab-students').classList.contains('on')) {
        loadStudents();
        loadStudentSummary();
      }
    };

    // ================= 7. STUDENT MANAGEMENT =================
    let studentSearchDebounce = null;
    let allRoomsCache = [];

    function debounceFilterStudents() {
      clearTimeout(studentSearchDebounce);
      studentSearchDebounce = setTimeout(() => {
        loadStudents();
      }, 300);
    }

    async function loadStudentClassFilter() {
      try {
        const classes = await fetchApi('/api/students/classes');
        const select = $('stClassFilter');
        if (!select) return;
        const currentVal = select.value;
        select.innerHTML = '<option value="all">Tất cả các lớp</option>';
        if (Array.isArray(classes)) {
          classes.forEach(c => {
            const opt = document.createElement('option');
            opt.value = c;
            opt.textContent = 'Lớp ' + c;
            select.appendChild(opt);
          });
        }
        if (currentVal) select.value = currentVal;
      } catch (err) {
        console.error('Lỗi tải danh sách lớp:', err);
      }
    }

    async function loadStudentSummary() {
      try {
        const summary = await fetchApi('/api/students/summary');
        if (!summary) return;
        if ($('stStatTotal')) $('stStatTotal').textContent = summary.total || 0;
        if ($('stStatAssigned')) $('stStatAssigned').textContent = summary.assigned || 0;
        if ($('stStatUnassigned')) $('stStatUnassigned').textContent = summary.unassigned || 0;
        if ($('stStatViolations')) $('stStatViolations').textContent = summary.totalViolations || 0;
        if ($('stStatParticipations')) $('stStatParticipations').textContent = summary.totalParticipations || 0;
      } catch (err) {
        console.error('Lỗi tải thống kê học sinh:', err);
      }
    }

    async function loadStudents() {
      const tbody = $('studentTableBody');
      if (!tbody) return;
      tbody.innerHTML = '<tr><td colspan="10" style="text-align:center; padding: 24px; color: var(--text-muted);">Đang nạp danh sách học sinh...</td></tr>';

      try {
        const search = $('stSearchInput') ? $('stSearchInput').value.trim() : '';
        const className = $('stClassFilter') ? $('stClassFilter').value : 'all';
        const status = $('stStatusFilter') ? $('stStatusFilter').value : 'all';

        let url = '/api/students?';
        const params = [];
        if (search) params.push('search=' + encodeURIComponent(search));
        if (className && className !== 'all') params.push('className=' + encodeURIComponent(className));
        if (status && status !== 'all') params.push('status=' + encodeURIComponent(status));
        url += params.join('&');

        const students = await fetchApi(url);
        if (!Array.isArray(students) || students.length === 0) {
          tbody.innerHTML = '<tr><td colspan="10" style="text-align:center; padding: 24px; color: var(--text-muted);">Không tìm thấy học sinh nào phù hợp tiêu chí.</td></tr>';
          return;
        }

        // Cache rooms if needed
        if (allRoomsCache.length === 0) {
          try {
            allRoomsCache = await fetchApi('/api/rooms') || [];
          } catch (_) {}
        }

        const roomMap = {};
        allRoomsCache.forEach(r => { roomMap[r.id] = r.code; });

        let html = '';
        students.forEach(s => {
          const roomCode = s.roomId ? (roomMap[s.roomId] || ('Phòng ' + s.roomId)) : 'Chưa xếp phòng';
          const seatDisplay = s.seatCode ? `<b style="font-family: var(--mono); color: var(--accent);">${s.seatCode}</b>` : `<span class="badge warning">Chờ xếp chỗ</span>`;
          
          let statusBadge = '';
          if (s.status === 'studying') {
            statusBadge = `<span class="badge success">Đang học</span>`;
          } else if (s.status === 'unassigned') {
            statusBadge = `<span class="badge warning">Chờ xếp chỗ</span>`;
          } else {
            statusBadge = `<span class="badge danger">Vắng</span>`;
          }

          const violBadge = s.violationCount > 0 
            ? `<span class="badge danger">${s.violationCount}</span>` 
            : `<span style="color: var(--text-muted);">0</span>`;
          const partBadge = s.participationCount > 0 
            ? `<span class="badge" style="background: #e0f2fe; color: #0284c7; font-weight: 600;">${s.participationCount}</span>` 
            : `<span style="color: var(--text-muted);">0</span>`;

          const escapedStudent = encodeURIComponent(JSON.stringify(s));

          html += `
            <tr>
              <td><b style="font-family: var(--mono); font-size: 13px;">${s.studentCode}</b></td>
              <td><b>${s.fullName}</b></td>
              <td><span class="badge" style="background: var(--surface-muted); color: var(--text); border: 1px solid var(--border);">${s.className || '12A1'}</span></td>
              <td>${s.gender || 'Nam'}</td>
              <td>${roomCode}</td>
              <td>${seatDisplay}</td>
              <td style="text-align: center;">${violBadge}</td>
              <td style="text-align: center;">${partBadge}</td>
              <td>${statusBadge}</td>
              <td>
                <div style="display: flex; gap: 6px;">
                  <button class="button small" onclick="openAssignSeatModal(decodeURIComponent('${escapedStudent}'))">Xếp chỗ</button>
                  <button class="button small" onclick="openEditStudentModal(decodeURIComponent('${escapedStudent}'))">Sửa</button>
                  <button class="button small danger" onclick="deleteStudent(${s.id}, '${s.fullName.replace(/'/g, "\\'")}')">Xóa</button>
                </div>
              </td>
            </tr>
          `;
        });

        tbody.innerHTML = html;
      } catch (err) {
        tbody.innerHTML = `<tr><td colspan="10" style="text-align:center; padding: 24px; color: var(--danger);">Lỗi khi nạp dữ liệu: ${err.message}</td></tr>`;
      }
    }

    function openCreateStudentModal() {
      $('studentFormId').value = '';
      $('studentFormCode').value = '';
      $('studentFormCode').readOnly = false;
      $('studentFormName').value = '';
      $('studentFormClass').value = '12A1';
      $('studentFormGender').value = 'Nam';
      $('studentFormDob').value = '2008-01-01';
      $('studentFormPhone').value = '';
      $('studentFormEmail').value = '';
      $('studentFormNotes').value = '';
      $('modalStudentFormTitle').textContent = 'Thêm Học sinh Mới';
      openModal('modalStudentForm');
    }

    function openEditStudentModal(raw) {
      const s = typeof raw === 'string' ? JSON.parse(raw) : raw;
      $('studentFormId').value = s.id;
      $('studentFormCode').value = s.studentCode || '';
      $('studentFormCode').readOnly = true;
      $('studentFormName').value = s.fullName || '';
      $('studentFormClass').value = s.className || '12A1';
      $('studentFormGender').value = s.gender || 'Nam';
      $('studentFormDob').value = s.dateOfBirth || '2008-01-01';
      $('studentFormPhone').value = s.phone || '';
      $('studentFormEmail').value = s.email || '';
      $('studentFormNotes').value = s.notes || '';
      $('modalStudentFormTitle').textContent = 'Chỉnh sửa Thông tin Học sinh';
      openModal('modalStudentForm');
    }

    async function saveStudent() {
      const id = $('studentFormId').value;
      const studentCode = $('studentFormCode').value.trim();
      const fullName = $('studentFormName').value.trim();
      const className = $('studentFormClass').value.trim();
      const gender = $('studentFormGender').value;
      const dateOfBirth = $('studentFormDob').value;
      const phone = $('studentFormPhone').value.trim();
      const email = $('studentFormEmail').value.trim();
      const notes = $('studentFormNotes').value.trim();

      if (!studentCode) { alert('Vui lòng nhập Mã học sinh!'); return; }
      if (!fullName) { alert('Vui lòng nhập Họ và tên học sinh!'); return; }

      const body = {
        studentCode, fullName, className, gender, dateOfBirth, phone, email, notes
      };

      try {
        if (id) {
          await fetchApi('/api/students/' + id, { method: 'PUT', body });
          showToast('Đã cập nhật thông tin học sinh ' + fullName);
        } else {
          await fetchApi('/api/students', { method: 'POST', body });
          showToast('Đã thêm mới học sinh ' + fullName);
        }
        closeModal('modalStudentForm');
        loadStudents();
        loadStudentSummary();
        loadStudentClassFilter();
      } catch (err) {
        alert(err.message || 'Lỗi khi lưu thông tin học sinh');
      }
    }

    async function deleteStudent(id, name) {
      if (!confirm(`Bạn có chắc chắn muốn xóa học sinh "${name}" khỏi hệ thống?`)) return;
      try {
        await fetchApi('/api/students/' + id, { method: 'DELETE' });
        showToast('Đã xóa học sinh ' + name);
        loadStudents();
        loadStudentSummary();
        if (currentRoomId) loadSeats();
      } catch (err) {
        alert(err.message || 'Lỗi khi xóa học sinh');
      }
    }

    let currentAssignStudent = null;

    async function openAssignSeatModal(raw) {
      const s = typeof raw === 'string' ? JSON.parse(raw) : raw;
      currentAssignStudent = s;
      $('assignStudentId').value = s.id;
      $('assignStudentInfo').value = `${s.studentCode} - ${s.fullName} (Lớp ${s.className || '12A1'})`;

      try {
        const rooms = await fetchApi('/api/rooms');
        const roomSelect = $('assignRoomSelect');
        roomSelect.innerHTML = '';
        if (Array.isArray(rooms)) {
          rooms.forEach(r => {
            const opt = document.createElement('option');
            opt.value = r.id;
            opt.textContent = `${r.code} - ${r.name}`;
            if (s.roomId === r.id || (!s.roomId && r.id === currentRoomId)) {
              opt.selected = true;
            }
            roomSelect.appendChild(opt);
          });
        }
        await onAssignRoomChanged();
        openModal('modalAssignSeat');
      } catch (err) {
        alert('Lỗi khi nạp danh sách phòng: ' + err.message);
      }
    }

    async function onAssignRoomChanged() {
      const roomId = $('assignRoomSelect').value;
      const seatSelect = $('assignSeatSelect');
      seatSelect.innerHTML = '<option value="">Chờ xếp chỗ (Không xếp ghế)</option>';
      if (!roomId) return;

      try {
        const seats = await fetchApi('/api/rooms/' + roomId + '/seats');
        if (Array.isArray(seats)) {
          seats.forEach(st => {
            const opt = document.createElement('option');
            opt.value = st.seatCode;
            const currentHolder = st.studentName ? ` (Đang ngồi: ${st.studentName})` : ' [Còn trống]';
            opt.textContent = `${st.seatCode}${currentHolder}`;
            if (currentAssignStudent && currentAssignStudent.roomId == roomId && currentAssignStudent.seatCode === st.seatCode) {
              opt.selected = true;
            }
            seatSelect.appendChild(opt);
          });
        }
      } catch (err) {
        console.error('Lỗi nạp ghế ngồi:', err);
      }
    }

    async function confirmAssignSeat() {
      const studentId = $('assignStudentId').value;
      const roomId = $('assignRoomSelect').value;
      const seatCode = $('assignSeatSelect').value;

      try {
        await fetchApi(`/api/students/${studentId}/assign-seat`, {
          method: 'POST',
          body: { roomId: roomId ? parseInt(roomId) : null, seatCode: seatCode || null }
        });
        showToast('Đã phân công chỗ ngồi thành công');
        closeModal('modalAssignSeat');
        loadStudents();
        loadStudentSummary();
        if (currentRoomId) loadSeats();
      } catch (err) {
        alert(err.message || 'Lỗi khi phân công chỗ ngồi');
      }
    }

    async function autoAssignSeats() {
      if (!currentRoomId) {
        alert('Vui lòng chọn một phòng học trước!');
        return;
      }
      const className = $('stClassFilter') ? $('stClassFilter').value : 'all';
      try {
        const res = await fetchApi(`/api/students/auto-assign/${currentRoomId}?className=${encodeURIComponent(className)}`, {
          method: 'POST'
        });
        showToast(res.message || 'Đã tự động xếp chỗ thành công!');
        loadStudents();
        loadStudentSummary();
        loadSeats();
      } catch (err) {
        alert(err.message || 'Lỗi khi tự động xếp chỗ');
      }
    }

    if ($('btnOpenCreateStudent')) $('btnOpenCreateStudent').onclick = openCreateStudentModal;
    if ($('btnSaveStudent')) $('btnSaveStudent').onclick = saveStudent;
    if ($('btnConfirmAssignSeat')) $('btnConfirmAssignSeat').onclick = confirmAssignSeat;
    if ($('btnAutoAssignSeats')) $('btnAutoAssignSeats').onclick = autoAssignSeats;
    if ($('stSearchInput')) $('stSearchInput').oninput = debounceFilterStudents;

    (async () => {
      try {
        // 1. Get current logged-in user profile & role
        const me = await fetchApi('/api/me');
        if (me) {
          currentUser = me;
          $('userName').textContent = me.fullName || me.username;
          $('userRole').textContent = me.role === 'ADMIN' ? 'Quản trị viên' : 'Giáo viên';
          $('userAvatar').textContent = (me.fullName || me.username).charAt(0).toUpperCase();

          // Show Admin exclusive tabs if role is ADMIN
          if (me.role === 'ADMIN') {
            $('navAdminGroupTitle').style.display = 'block';
            $('navAdminUsers').style.display = 'flex';
            $('navAdminRooms').style.display = 'flex';
            $('btnConfigSeatingAdmin').style.display = 'inline-flex';
          }
        }

        // 2. Load Rooms
        await initRooms();

        // 3. Load initial view: Camera & Seating map is active by default
        loadSeats();
        checkCameraStatus();
        loadBehaviorData();
        loadBehaviorChart();
        loadEnvStatus();

        // Periodic background refresh (1.2s for real-time camera & seating map sync)
        setInterval(() => {
          const camSeatTab = $('tab-camera-seating');
          if (camSeatTab && camSeatTab.classList.contains('on')) {
            loadBehaviorData().catch(() => {});
            checkCameraStatus().catch(() => {});
            loadSeats().catch(() => {});
          }
          const dashTab = $('tab-dashboard');
          if (dashTab && dashTab.classList.contains('on')) {
            loadEnvStatus().catch(() => {});
          }
        }, 1200);

        setInterval(() => {
          loadEnvChart().catch(() => {});
        }, 30000);

      } catch (e) {
        console.error('Initialization error:', e);
      }
    })();