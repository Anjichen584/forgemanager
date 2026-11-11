"""极简 RCON 客户端（Source RCON 协议），只依赖标准库。

用法：
    python tools/rcon.py --host 127.0.0.1 --port 25599 --password testpass "zhujian selftest"
    python tools/rcon.py ... --file commands.txt   # 每行一条命令，逐条发送

退出码：0 = 认证并执行成功；1 = 连接/认证失败。
多包响应会自动拼接（Server 端可能把长输出拆成多个响应包）。
"""
import argparse
import socket
import struct
import sys
import time

SERVERDATA_AUTH = 3
SERVERDATA_AUTH_RESPONSE = 2
SERVERDATA_EXECCOMMAND = 2
SERVERDATA_RESPONSE_VALUE = 0


def send_packet(sock, req_id: int, ptype: int, payload: bytes) -> None:
    data = struct.pack("<ii", req_id, ptype) + payload + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(data)) + data)


def recv_exact(sock, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("连接被服务端关闭")
        buf += chunk
    return buf


def recv_packet(sock):
    (length,) = struct.unpack("<i", recv_exact(sock, 4))
    body = recv_exact(sock, length)
    req_id, ptype = struct.unpack("<ii", body[:8])
    payload = body[8:-2]
    return req_id, ptype, payload


def run_command(sock, req_id: int, command: str, settle_seconds: float = 1.0) -> str:
    """发一条命令并收完整响应；长输出可能拆包，静默 1s 无新包即认为收完。"""
    send_packet(sock, req_id, SERVERDATA_EXECCOMMAND, command.encode("utf-8"))
    parts = []
    last = time.time()
    while time.time() - last < settle_seconds:
        sock.settimeout(0.5)
        try:
            rid, ptype, payload = recv_packet(sock)
        except socket.timeout:
            continue
        except (ConnectionResetError, OSError):
            break  # 服务端关连接（如 stop 后）= 输出已结束
        if ptype == SERVERDATA_RESPONSE_VALUE:
            parts.append(payload.decode("utf-8", errors="replace"))
        last = time.time()
    return "".join(parts)


def login(sock, password: str) -> bool:
    """RCON 认证；成功 True。未认证的命令服务端会静默丢弃——所有会话先 login。"""
    send_packet(sock, 1, SERVERDATA_AUTH, password.encode("utf-8"))
    try:
        rid, ptype, _ = recv_packet(sock)
    except (ConnectionResetError, OSError):
        return False
    return rid != -1


def run_streaming(sock, req_id: int, command: str, done, timeout: float = 240.0,
                  idle_timeout: float = 30.0):
    """发一条命令并持续收包，直到 done(已收文本) 返回 True / 总超时 / 静默 idle_timeout。

    自检这类会回几百行结果的长命令用这个；普通短命令用 run_command。
    返回 (完整文本, 是否命中 done)。
    """
    send_packet(sock, req_id, SERVERDATA_EXECCOMMAND, command.encode("utf-8"))
    parts = []
    start = time.time()
    last = time.time()
    while True:
        if done("".join(parts)):
            return "".join(parts), True
        if time.time() - start > timeout or time.time() - last > idle_timeout:
            return "".join(parts), False
        sock.settimeout(1.0)
        try:
            rid, ptype, payload = recv_packet(sock)
        except socket.timeout:
            continue
        except (ConnectionResetError, OSError):
            return "".join(parts), False
        if ptype == SERVERDATA_RESPONSE_VALUE:
            parts.append(payload.decode("utf-8", errors="replace"))
            last = time.time()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("command", nargs="?", help="要执行的命令")
    ap.add_argument("--file", help="逐行执行文件里的命令")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25599)
    ap.add_argument("--password", default="testpass")
    ap.add_argument("--settle", type=float, default=1.0, help="判定响应收尾的静默秒数")
    args = ap.parse_args()

    if not args.command and not args.file:
        ap.error("需要 command 或 --file 之一")

    try:
        sock = socket.create_connection((args.host, args.port), timeout=10)
    except OSError as ex:
        print(f"[rcon] 连接失败 {args.host}:{args.port} — {ex}", file=sys.stderr)
        return 1

    try:
        send_packet(sock, 1, SERVERDATA_AUTH, args.password.encode("utf-8"))
        rid, ptype, _ = recv_packet(sock)
        if rid == -1:
            print("[rcon] 认证失败：密码不对", file=sys.stderr)
            return 1

        commands = [args.command] if args.command else [
            line.strip() for line in open(args.file, encoding="utf-8")
            if line.strip() and not line.strip().startswith("#")
        ]
        for i, cmd in enumerate(commands, 1):
            out = run_command(sock, i + 10, cmd, args.settle)
            print(f"--- [{i}/{len(commands)}] {cmd}")
            print(out or "(空响应)")
        return 0
    finally:
        sock.close()


if __name__ == "__main__":
    sys.exit(main())
