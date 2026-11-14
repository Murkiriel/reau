"""Listeners on the host for `LocalNetworkProbeTest` (plan 25): one TCP port and one UDP port, the same number, on every interface; each connection
or datagram that arrives is printed. The emulator reaches the host as 10.0.2.2.

Usage:  python3 scripts/emu/escutas_rede_local.py [PORT] [SECONDS]      (default 5599, 60 s)
"""
import socket
import sys
import threading
import time


def main(port, seconds):
    stop = time.time() + seconds
    tcp = socket.socket()
    tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    tcp.bind(("0.0.0.0", port))
    tcp.listen(4)
    tcp.settimeout(1)
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(("0.0.0.0", port))
    udp.settimeout(1)

    def serve_udp():
        while time.time() < stop:
            try:
                data, who = udp.recvfrom(64)
                print(f"UDP from {who[0]}: {data!r}", flush=True)
            except socket.timeout:
                pass

    threading.Thread(target=serve_udp, daemon=True).start()
    while time.time() < stop:
        try:
            conn, who = tcp.accept()
            print(f"TCP from {who[0]}", flush=True)
            conn.close()
        except socket.timeout:
            pass


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 5599, int(sys.argv[2]) if len(sys.argv) > 2 else 60)
