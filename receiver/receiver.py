import socket
import json
import pyautogui
import sys

HOST = "0.0.0.0"
PORT = 3001

MODE = sys.argv[1].lower() if len(sys.argv) > 1 else "quiet"

pyautogui.PAUSE = 0
pyautogui.FAILSAFE = False

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((HOST, PORT))

if MODE == "debug":
    print(f"DEBUG RECEIVER: listening on {HOST}:{PORT}")
else:
    print(f"Receiver listening on {HOST}:{PORT}")

if MODE == "debug":
    while True:
        print("Waiting for packet...")
        data, addr = sock.recvfrom(2048)

        print("\n--- PACKET RECEIVED ---")
        print("FROM:", addr)
        print("RAW:", data)

        try:
            packet = json.loads(data.decode("utf-8"))
            print("JSON:", packet)
        except Exception as e:
            print("JSON ERROR:", e)
            continue

        packet_type = packet.get("type")
        print("TYPE:", packet_type)

        if packet_type == "move":
            dx = float(packet.get("dx", 0))
            dy = float(packet.get("dy", 0))
            print("MOVING:", dx, dy)
            pyautogui.moveRel(dx, dy, duration=0)

        elif packet_type == "click":
            seq = packet.get("seq")
            print("CLICK:", seq)
            pyautogui.click()
            ack = {"type": "ack", "seq": seq}
            sock.sendto(json.dumps(ack).encode("utf-8"), addr)
            print("ACK SENT:", ack)

        elif packet_type == "scroll":
            seq = packet.get("seq")
            amount = int(packet.get("amount", 0))
            print("SCROLL:", seq, amount)
            pyautogui.scroll(amount)
            ack = {"type": "ack", "seq": seq}
            sock.sendto(json.dumps(ack).encode("utf-8"), addr)
            print("ACK SENT:", ack)

        else:
            print("UNKNOWN TYPE")

else:
    while True:
        data, addr = sock.recvfrom(2048)
        try:
            packet = json.loads(data)  
        except Exception:
            continue

        packet_type = packet.get("type")

        if packet_type == "move":
            pyautogui.moveRel(
                float(packet.get("dx", 0)),
                float(packet.get("dy", 0)),
                duration=0
            )

        elif packet_type == "click":
            seq = packet.get("seq")
            pyautogui.click()
            sock.sendto(json.dumps({"type": "ack", "seq": seq}).encode(), addr)

        elif packet_type == "scroll":
            seq = packet.get("seq")
            pyautogui.scroll(int(packet.get("amount", 0)))
            sock.sendto(json.dumps({"type": "ack", "seq": seq}).encode(), addr)

