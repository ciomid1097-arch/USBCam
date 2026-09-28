"""Offscreen test: simulate mouse events to verify frameless move + edge-resize."""
import os
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
import sys
sys.path.insert(0, os.path.dirname(__file__))

from PySide6.QtCore import Qt, QPointF
from PySide6.QtGui import QMouseEvent
from PySide6.QtWidgets import QApplication

import receiver

app = QApplication([])
win = receiver.VideoWindow()
win.resize(800, 500)
win.move(100, 100)
win.show()
app.processEvents()


def ev(event_type, local, global_pt, button, buttons):
    lp, gp = QPointF(local[0], local[1]), QPointF(global_pt[0], global_pt[1])
    return QMouseEvent(event_type, lp, gp, button, buttons, Qt.NoModifier)


def do(local, delta):
    """Press at window-local `local`, move by `delta` (global), release."""
    tl = win.mapToGlobal(win.rect().topLeft())
    g_start = (tl.x() + local[0], tl.y() + local[1])
    g_end = (g_start[0] + delta[0], g_start[1] + delta[1])
    win.mousePressEvent(ev(QMouseEvent.MouseButtonPress, local, g_start,
                           Qt.LeftButton, Qt.LeftButton))
    # move/release only use the global position inside the handler
    win.mouseMoveEvent(ev(QMouseEvent.MouseMove, g_end, g_end,
                          Qt.NoButton, Qt.LeftButton))
    win.mouseReleaseEvent(ev(QMouseEvent.MouseButtonRelease, g_end, g_end,
                             Qt.NoButton, Qt.NoButton))
    app.processEvents()
    return win.x(), win.y(), win.width(), win.height()


r = do((400, 250), (150, -20))          # middle: move window
print("move      ->", r, "expect (250, 80, 800, 500)")
assert r == (250, 80, 800, 500), "move failed"

r = do((799, 499), (60, 40))            # bottom-right corner: grow
print("BR grow   ->", r, "expect (250, 80, 860, 540)")
assert r == (250, 80, 860, 540), "BR resize failed"

r = do((0, 0), (-30, -25))              # top-left corner: grow up-left
print("TL grow   ->", r, "expect (220, 55, 890, 565)")
assert r == (220, 55, 890, 565), "TL resize failed"

r = do((889, 300), (-700, 0))           # right edge: shrink width
print("R shrink  ->", r, "expect (220, 55, 190, 565)")
assert r == (220, 55, 190, 565), "R shrink failed"

r = do((189, 564), (0, -600))           # bottom edge: shrink height to min
print("min clamp ->", r, "expect (220, 55, 190, 90)")
assert r == (220, 55, 190, 90), "min clamp failed"

win.close()
print("move + resize logic OK")
