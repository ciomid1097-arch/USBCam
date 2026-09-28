"""Headless protocol check: listen like the receiver, wait for the phone, report fps for 3 s."""
import socket
import struct
import time

srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", 8420))
srv.listen(1)
srv.settimeout(15)
print("listening on 127.0.0.1:8420 …")
s, _ = srv.accept()
s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
s.settimeout(5)
print("phone connected")

frames = 0
sizes = []
t0 = None
payload = b""
while True:
    hdr = b""
    while len(hdr) < 4:
        chunk = s.recv(4 - len(hdr))
        if not chunk:
            raise SystemExit("closed")
        hdr += chunk
    (n,) = struct.unpack("<I", hdr)
    payload = b""
    while len(payload) < n:
        chunk = s.recv(min(n - len(payload), 256 * 1024))
        if not chunk:
            raise SystemExit("closed")
        payload += chunk
    if t0 is None:
        t0 = time.time()
        print(f"first frame: cam_byte={payload[0]} jpeg_magic={payload[1:3].hex()} len={n}")
    frames += 1
    sizes.append(n)
    if time.time() - t0 >= 3.0:
        break

dt = time.time() - t0
print(f"frames={frames} fps={frames / dt:.1f} avg={sum(sizes) // len(sizes)}B max={max(sizes)}B")
