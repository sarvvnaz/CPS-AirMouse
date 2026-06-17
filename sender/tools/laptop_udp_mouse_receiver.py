import json
import socket
from typing import Set, Tuple

try:
    import pyautogui
except Exception:
    pyautogui = None

HOST = "0.0.0.0"
PORT = 5000

processed_sequences: Set[Tuple[str, int]] = set()


def send_ack(sock: socket.socket, addr, seq: int) -> None:
    ack = json.dumps({"type": "ack", "seq": seq}).encode("utf-8")
    sock.sendto(ack, addr)


def main() -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((HOST, PORT))
    print(f"Listening on UDP {HOST}:{PORT}")
    print("Phone and laptop must be on the same network.")
    if pyautogui is None:
        print("pyautogui not installed. Packets will be printed only.")

    while True:
        data, addr = sock.recvfrom(2048)
        text = data.decode("utf-8", errors="replace")
        try:
            msg = json.loads(text)
        except json.JSONDecodeError:
            print("Invalid JSON:", text)
            continue

        msg_type = msg.get("type")

        if msg_type == "move":
            dx = float(msg.get("dx", 0.0))
            dy = float(msg.get("dy", 0.0))
            print(f"MOVE dx={dx:.2f} dy={dy:.2f}")
            if pyautogui is not None:
                pyautogui.moveRel(dx, dy, duration=0)

        elif msg_type == "click":
            seq = int(msg.get("seq", -1))
            if seq < 0:
                continue
            key = ("click", seq)
            duplicate = key in processed_sequences
            if not duplicate:
                processed_sequences.add(key)
                print(f"CLICK seq={seq}")
                if pyautogui is not None:
                    pyautogui.click()
            else:
                print(f"DUPLICATE CLICK seq={seq}; ACK re-sent")
            send_ack(sock, addr, seq)

        elif msg_type == "scroll":
            seq = int(msg.get("seq", -1))
            amount = int(msg.get("amount", 0))
            if seq < 0:
                continue
            key = ("scroll", seq)
            duplicate = key in processed_sequences
            if not duplicate:
                processed_sequences.add(key)
                print(f"SCROLL seq={seq} amount={amount}")
                if pyautogui is not None:
                    pyautogui.scroll(amount)
            else:
                print(f"DUPLICATE SCROLL seq={seq}; ACK re-sent")
            send_ack(sock, addr, seq)

        else:
            print("Unknown packet:", msg)


if __name__ == "__main__":
    main()
