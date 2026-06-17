import socket
import json
import pyautogui

HOST = "0.0.0.0"
PORT = 5000

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((HOST, PORT))

print(f"DEBUG RECEIVER: listening on {HOST}:{PORT}")

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
