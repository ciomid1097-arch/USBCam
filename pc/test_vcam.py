"""Real virtual-camera test: open OBS Virtual Camera via the app's own code path
with a synthetic frame and send 30 frames. Prints the exact failure if any."""
import os
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
import sys
sys.path.insert(0, os.path.dirname(__file__))

from PySide6.QtCore import Qt
from PySide6.QtGui import QImage, QPainter, QColor, QPixmap
from PySide6.QtWidgets import QApplication

import receiver

app = QApplication([])
win = receiver.VideoWindow()

# synthetic 1280x720 frame (moving bars)
img = QImage(1280, 720, QImage.Format_RGB888)
p = QPainter(img)
for i in range(0, 1280, 80):
    p.fillRect(i, 0, 40, 720, QColor(i % 255, 100, 200))
p.end()
win._pixmap = QPixmap.fromImage(img)

print("virtual cam available:", receiver.VIRTUAL_CAM_AVAILABLE)
win._start_vcam()
print("after start: cam =", win._vcam, "| error =", win._vcam_error)
if win._vcam is None:
    print("FAILED TO OPEN:", win._vcam_error)
    sys.exit(1)

for i in range(30):
    win._send_to_vcam(img)
if win._vcam is None:
    print("SEND FAILED:", win._vcam_error)
    sys.exit(1)
print("after 30 sends: error =", win._vcam_error, "| device:", win._vcam.device)
win._stop_vcam()
print("VCAM PIPELINE OK")
