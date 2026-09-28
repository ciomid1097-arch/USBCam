#!/usr/bin/env python3
"""
USBCam receiver — shows the phone camera over USB in a borderless, freely
resizable window (for OBS Window Capture).

- Finds adb automatically (SDK path or PATH), runs `adb reverse tcp:PORT tcp:PORT`
  every few seconds (heals cable replugs), and listens on 127.0.0.1:PORT.
- Protocol per frame (little-endian): u32 payloadLength, then payloadLength bytes;
  payload = 1 camera byte (0=back, 1=front) + JPEG bytes.
- Never buffers: on every connect it resyncs at the stream head, so latency
  never grows. The window draws ONLY the video — nothing else, ever.

Controls (right-click anywhere):
    Always on top / Mirror / Fullscreen / Close
    Esc leaves fullscreen, F11 toggles it, left-drag moves the window.
"""
import os
import socket
import struct
import subprocess
import sys
import threading
import time

from PySide6.QtCore import Qt, QTimer, Signal, QThread, QRect
from PySide6.QtGui import QImage, QPainter, QPixmap
from PySide6.QtWidgets import QApplication, QMainWindow, QMenu

try:
    import numpy as np
    import pyvirtualcam
    VIRTUAL_CAM_AVAILABLE = True
except ImportError:
    VIRTUAL_CAM_AVAILABLE = False

PORT = 8420
CHUNK = 256 * 1024

# Prevent console windows from flashing on every adb call in the windowed exe.
CREATE_NO_WINDOW = 0x08000000 if os.name == "nt" else 0


def run_quiet(cmd, **kw):
    """subprocess.run without any visible console window (Windows)."""
    kw.setdefault("creationflags", CREATE_NO_WINDOW)
    return subprocess.run(cmd, **kw)


def find_adb() -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    candidates = [
        os.path.join(sdk, "platform-tools", "adb.exe"),
        os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
        "adb",
    ]
    for c in candidates:
        if not c or c == "adb":
            c2 = "adb"
        else:
            c2 = c
        try:
            run_quiet([c2, "version"], stdout=subprocess.DEVNULL,
                      stderr=subprocess.DEVNULL, timeout=5, check=True)
            return c2
        except Exception:
            continue
    raise FileNotFoundError("adb not found — install Android platform-tools")


class AdbSetupThread(QThread):
    """Runs `adb reverse` (and starts adb if needed), then keeps it alive."""
    status = Signal(str)

    def __init__(self, port=PORT):
        super().__init__()
        self.port = port
        self._stop = False

    def run(self):
        try:
            adb = find_adb()
        except FileNotFoundError as e:
            self.status.emit(str(e))
            return
        while not self._stop:
            try:
                run_quiet([adb, "start-server"], stdout=subprocess.DEVNULL,
                          stderr=subprocess.DEVNULL, timeout=15)
                run_quiet([adb, "reverse", f"tcp:{self.port}", f"tcp:{self.port}"],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
                devices = run_quiet([adb, "devices"], stdout=subprocess.PIPE,
                                    stderr=subprocess.DEVNULL, timeout=10
                                    ).stdout.decode(errors="replace")
                self.status.emit("phone connected"
                                 if "device" in devices else "no phone — plug the USB cable")
            except Exception as e:
                self.status.emit(f"adb error: {e}")
            for _ in range(100):  # sleep 5 s in small steps so stop stays responsive
                if self._stop:
                    return
                time.sleep(0.05)

    def stop(self):
        self._stop = True


class FrameReader(threading.Thread):
    """TCP server on 127.0.0.1:PORT (the phone connects via `adb reverse`).
    Keeps only the newest complete JPEG for the GUI thread."""

    def __init__(self, host="127.0.0.1", port=PORT):
        super().__init__(daemon=True)
        self.server = None
        self.sock = None          # current phone connection (for status display)
        self.lock = threading.Lock()
        self.current = None       # latest full frame (bytes, JPEG)
        self.counter = 0
        self.host = host
        self.port = port
        self.stop_flag = False

    def stop(self):
        self.stop_flag = True
        for s in (self.sock, self.server):
            try:
                if s:
                    s.shutdown(socket.SHUT_RDWR)
                    s.close()
            except OSError:
                pass

    def run(self):
        self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind((self.host, self.port))
        self.server.listen(2)
        while not self.stop_flag:
            try:
                conn, _ = self.server.accept()
            except OSError:
                break
            try:
                conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                conn.settimeout(3)
                self.sock = conn
                with self.lock:           # fresh start: throw away stale frames
                    self.current = None
                    self.counter = 0
                while not self.stop_flag:
                    self.read_frame(conn)
            except OSError:
                pass
            finally:
                try:
                    conn.close()
                except OSError:
                    pass
                self.sock = None

    def read_frame(self, s):
        header = self.recv_exact(s, 4)          # any error here -> reconnect+resync
        (length,) = struct.unpack("<I", header)
        if length <= 1 or length > 20 * 1024 * 1024:
            raise OSError("bad frame length")
        payload = self.recv_exact(s, length)
        with self.lock:
            self.current = payload[1:]          # strip camera byte; payload = JPEG
            self.counter += 1

    @staticmethod
    def recv_exact(s, n):
        chunks = []
        while n > 0:
            chunk = s.recv(min(n, CHUNK))       # timeout/close raise OSError
            if not chunk:
                raise OSError("closed")
            chunks.append(chunk)
            n -= len(chunk)
        return b"".join(chunks)

    def get_frame(self):
        with self.lock:
            return self.current, self.counter


class VideoWindow(QMainWindow):
    """Borderless window showing ONLY the video."""

    def __init__(self):
        super().__init__()
        self.setWindowFlags(Qt.FramelessWindowHint | Qt.Window)
        self.setMinimumSize(160, 90)
        self.setWindowTitle("USBCam")
        self.setMouseTracking(True)   # cursor feedback on edges without buttons held

        self.reader = FrameReader()
        self.reader.start()

        self.adb_thread = AdbSetupThread()
        self.adb_thread.status.connect(self._on_adb_status)
        self.adb_thread.start()

        self.mirrored = False
        self.flip_v = False
        self._last_counter = -1
        self._pixmap = None
        self._drag_offset = None
        self._resize_mode = None
        self._resize_from = None
        self._move_offset = None
        self._fps_frames = 0
        self._fps_t0 = time.time()
        self._fps = 0.0
        self._status = "starting…"

        # Virtual camera (OBS Virtual Camera / Meet / Zoom …) — off by default.
        self._vcam = None
        self._vcam_error = None
        if not VIRTUAL_CAM_AVAILABLE:
            self._vcam_error = "pyvirtualcam/numpy not installed"

        self.timer = QTimer(self)
        self.timer.timeout.connect(self.tick)
        self.timer.start(15)

    # ---------- adb status ----------
    def _on_adb_status(self, msg):
        self._status = self._with_vcam(msg)

    def _with_vcam(self, msg):
        if self._vcam is not None:
            return msg + " — vcam ON"
        if self._vcam_error:
            return msg + " — vcam err"
        return msg

    # ---------- drawing ----------
    def paintEvent(self, ev):
        p = QPainter(self)
        p.fillRect(self.rect(), Qt.black)
        if self._pixmap is None:
            return
        scaled = self._pixmap.scaled(self.size(), Qt.KeepAspectRatio,
                                     Qt.SmoothTransformation)
        scaled = self._pixmap.scaled(self.size(), Qt.KeepAspectRatio,
                                     Qt.SmoothTransformation)
        x = (self.width() - scaled.width()) // 2
        y = (self.height() - scaled.height()) // 2
        sx = -1.0 if self.mirrored else 1.0
        sy = -1.0 if self.flip_v else 1.0
        if sx != 1.0 or sy != 1.0:
            p.save()
            p.translate(x + (scaled.width() if sx < 0 else 0),
                        y + (scaled.height() if sy < 0 else 0))
            p.scale(sx, sy)
            p.drawPixmap(0, 0, scaled)
            p.restore()
        else:
            p.drawPixmap(x, y, scaled)

    def tick(self):
        frame, counter = self.reader.get_frame()
        if counter == self._last_counter:
            if self.reader.sock is None:
                self.setWindowTitle(f"USBCam — {self._status} — no stream")
            return
        self._last_counter = counter
        img = QImage.fromData(frame, "JPEG")
        if not img.isNull():
            self._pixmap = QPixmap.fromImage(img)
            self._fps_frames += 1
            now = time.time()
            if now - self._fps_t0 >= 1.0:
                self._fps = self._fps_frames / (now - self._fps_t0)
                self._fps_frames = 0
                self._fps_t0 = now
                w, h = self._pixmap.width(), self._pixmap.height()
                self.setWindowTitle(f"USBCam — {self._status} — {w}x{h} @ {self._fps:.0f} fps")
            self._send_to_vcam(img)
            self.update()

    # ---------- virtual camera ----------
    def _send_to_vcam(self, img):
        """Forward the current frame to the virtual camera, if active."""
        if self._vcam is None:
            return
        try:
            w, h = self._vcam.width, self._vcam.height
            scaled = img.scaled(w, h, Qt.IgnoreAspectRatio, Qt.SmoothTransformation)
            scaled = scaled.convertToFormat(QImage.Format_BGR888)  # returns NEW image!
            buf = scaled.constBits()
            arr = np.frombuffer(buf, dtype=np.uint8)
            arr = arr.reshape(scaled.height(), scaled.bytesPerLine())[:, : w * 3]
            arr = arr.reshape(h, w, 3)   # pyvirtualcam wants (h, w, 3)
            self._vcam.send(np.ascontiguousarray(arr))
        except Exception as e:
            self._vcam_error = str(e)
            self._stop_vcam()

    def _start_vcam(self):
        if self._vcam is not None:
            return
        if not VIRTUAL_CAM_AVAILABLE:
            self._vcam_error = "pyvirtualcam/numpy not installed"
            return
        if self._pixmap is None:
            self._vcam_error = "no frame yet"
            return
        # Native size first; OBS driver caps at 1920x1080 (quirk: needs non-zero size).
        img = self._pixmap.toImage().convertToFormat(QImage.Format_BGR888)
        w, h = img.width(), img.height()
        if w == 0 or h == 0:
            self._vcam_error = "empty frame"
            return
        if w > 1920:
            h = int(h * 1920 / w)
            w = 1920
        if h > 1080:
            w = int(w * 1080 / h)
            h = 1080
        try:
            self._vcam = pyvirtualcam.Camera(width=w, height=h, fps=30)
            self._vcam_error = None
            self._status = self._with_vcam(self._status.split(" — vcam")[0])
        except Exception as e:
            self._vcam_error = str(e)
            self._vcam = None

    def _stop_vcam(self):
        if self._vcam is not None:
            try:
                self._vcam.close()
            except Exception:
                pass
        self._vcam = None
        self._status = self._with_vcam(self._status.split(" — vcam")[0])

    # ---------- menu / controls ----------
    def contextMenuEvent(self, ev):
        menu = QMenu(self)
        act_top = menu.addAction("Always on top")
        act_top.setCheckable(True)
        act_top.setChecked(bool(self.windowFlags() & Qt.WindowStaysOnTopHint))
        act_top.triggered.connect(self._toggle_top)
        act_mir = menu.addAction("Mirror")
        act_mir.setCheckable(True)
        act_mir.setChecked(self.mirrored)
        act_mir.triggered.connect(self._toggle_mirror)
        act_fv = menu.addAction("Flip vertical")
        act_fv.setCheckable(True)
        act_fv.setChecked(self.flip_v)
        act_fv.triggered.connect(self._toggle_flip_v)
        act_fs = menu.addAction("Fullscreen")
        act_fs.setCheckable(True)
        act_fs.setChecked(self.isFullScreen())
        act_fs.triggered.connect(self._toggle_fullscreen)
        menu.addSeparator()
        if VIRTUAL_CAM_AVAILABLE:
            act_vc = menu.addAction("Virtual camera (Meet/Zoom …)")
            act_vc.setCheckable(True)
            act_vc.setChecked(self._vcam is not None)
            act_vc.triggered.connect(self._toggle_vcam)
        elif self._vcam_error:
            act_vc = menu.addAction(f"Virtual camera unavailable ({self._vcam_error})")
            act_vc.setEnabled(False)
        menu.addSeparator()
        menu.addAction("Close", self.close)
        menu.exec(ev.globalPos())

    def _toggle_top(self):
        on = not (self.windowFlags() & Qt.WindowStaysOnTopHint)
        self.setWindowFlag(Qt.WindowStaysOnTopHint, on)
        self.show()

    def _toggle_mirror(self):
        self.mirrored = not self.mirrored
        self.update()

    def _toggle_flip_v(self):
        self.flip_v = not self.flip_v
        self.update()

    def _toggle_vcam(self):
        if self._vcam is not None:
            self._stop_vcam()
        else:
            self._start_vcam()

    def _toggle_fullscreen(self):
        if self.isFullScreen():
            self.showNormal()
        else:
            self.showFullScreen()

    def keyPressEvent(self, ev):
        if ev.key() == Qt.Key_Escape and self.isFullScreen():
            self.showNormal()
        elif ev.key() == Qt.Key_F11:
            self._toggle_fullscreen()

    # ---------- drag & resize (frameless: manual hit-test on edges) ----------
    M = 6  # resizable border width in px

    def _hit(self, pos):
        """Return which edges the point is near, e.g. 'L,T,' or '' for the middle."""
        m = self.M
        parts = ""
        if pos.x() <= m: parts += "L,"
        if pos.x() >= self.width() - m: parts += "R,"
        if pos.y() <= m: parts += "T,"
        if pos.y() >= self.height() - m: parts += "B,"
        return parts

    def _update_cursor(self, parts):
        c = {
            "L,": Qt.SizeHorCursor, "R,": Qt.SizeHorCursor,
            "T,": Qt.SizeVerCursor, "B,": Qt.SizeVerCursor,
            "L,T,": Qt.SizeFDiagCursor, "R,B,": Qt.SizeFDiagCursor,
            "R,T,": Qt.SizeBDiagCursor, "L,B,": Qt.SizeBDiagCursor,
        }.get(parts)
        if c:
            self.setCursor(c)
        else:
            self.unsetCursor()

    def mousePressEvent(self, ev):
        if ev.button() != Qt.LeftButton:
            return
        self._resize_mode = self._hit(ev.position().toPoint())
        if self._resize_mode:
            self._drag_offset = ev.globalPosition().toPoint()
            self._resize_from = self.frameGeometry()
        else:
            # Manual move: the reliable way for frameless windows.
            self._move_offset = ev.globalPosition().toPoint() - self.frameGeometry().topLeft()

    def mouseMoveEvent(self, ev):
        self._update_cursor(self._hit(ev.position().toPoint()))
        if not (ev.buttons() & Qt.LeftButton):
            return
        g = ev.globalPosition().toPoint()
        if self._resize_mode:
            base = self._resize_from
            new = QRect(base.topLeft(), base.size())
            if "L" in self._resize_mode:
                new.setLeft(min(g.x(), base.right() - self.minimumWidth()))
            if "T" in self._resize_mode:
                new.setTop(min(g.y(), base.bottom() - self.minimumHeight()))
            if "R" in self._resize_mode:
                new.setRight(max(g.x(), base.left() + self.minimumWidth() - 1))
            if "B" in self._resize_mode:
                new.setBottom(max(g.y(), base.top() + self.minimumHeight() - 1))
            self.setGeometry(new)
        elif self._move_offset is not None:
            self.move(g - self._move_offset)

    def mouseReleaseEvent(self, ev):
        self._resize_mode = None
        self._drag_offset = None
        self._move_offset = None

    def closeEvent(self, ev):
        self._stop_vcam()
        self.adb_thread.stop()
        self.adb_thread.wait(3000)
        self.reader.stop()
        super().closeEvent(ev)


def main():
    app = QApplication(sys.argv)
    win = VideoWindow()
    win.resize(1280, 720)
    win.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
