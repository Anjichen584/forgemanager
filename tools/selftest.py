"""一键自检：部署最新 jar → 启动测试服 → RCON 跑 /zhujian selftest → 收集结果 → 关服。

用法（在仓库根目录）：
    python tools/selftest.py                     # 默认 target 里最新的 ForgingMaster-*.jar
    python tools/selftest.py --jar target/ForgingMaster-2.17.3.jar
    python tools/selftest.py --keep              # 不关服（调试用，Ctrl+C 后手动 stop）

流程：
  1. 把 testserver/plugins 里的旧 ForgingMaster-*.jar 移到 testserver/_jar-backup/（可恢复）
  2. 复制 --jar（默认 target 最新）进 plugins/
  3. 启动 paper.jar（nogui），stdout 记到 testserver/_selftest-run.log
  4. 等 "Done (" 出现（最多 300 秒），RCON 发 /zhujian selftest，等结果写进日志
  5. 抓「共 N 项 / 通过 / 失败」汇总行打印；最后发 stop（除非 --keep）
"""
import argparse
import glob
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import rcon  # noqa: E402

DEFAULT_SERVER = r"D:\IDEA\mc-plugins\ForgingMaster\testserver"
RUN_LOG = "_selftest-run.log"
DONE_TIMEOUT = 300
RESULT_TIMEOUT = 180


def latest_jar() -> str:
    jars = sorted(glob.glob(os.path.join(REPO, "target", "ForgingMaster-*.jar")),
                  key=os.path.getmtime)
    if not jars:
        sys.exit("[selftest] target/ 里没有 ForgingMaster-*.jar，先 mvn package")
    return jars[-1]


def tail(path: str) -> str:
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def main() -> int:
    # 控制台常是 GBK，报告里有 ✓/✗/» 这类字符，强制 UTF-8 免得打印时炸在最后一行
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", default=None)
    ap.add_argument("--server", default=DEFAULT_SERVER)
    ap.add_argument("--rcon-port", type=int, default=25599)
    ap.add_argument("--password", default="testpass")
    ap.add_argument("--keep", action="store_true", help="跑完不关服")
    ap.add_argument("--skip-deploy", action="store_true", help="不动 plugins，直接启动")
    args = ap.parse_args()

    server = args.server
    if not os.path.isdir(server):
        sys.exit(f"[selftest] 测试服目录不存在：{server}")
    log_path = os.path.join(server, RUN_LOG)

    jar = args.jar or latest_jar()
    if not os.path.isfile(jar):
        sys.exit(f"[selftest] 找不到 {jar}")

    # ---- 1-2. 部署 ----
    if not args.skip_deploy:
        backup = os.path.join(server, "_jar-backup")
        os.makedirs(backup, exist_ok=True)
        for old in glob.glob(os.path.join(server, "plugins", "ForgingMaster-*.jar")):
            dest = os.path.join(backup, os.path.basename(old))
            shutil.move(old, dest)
            print(f"[selftest] 旧包移出：{os.path.basename(old)} → _jar-backup/")
        shutil.copy2(jar, os.path.join(server, "plugins", os.path.basename(jar)))
        print(f"[selftest] 部署：{os.path.basename(jar)}")

    # ---- 3. 启动 ----
    if os.path.exists(log_path):
        os.remove(log_path)
    log_f = open(log_path, "wb")
    proc = subprocess.Popen(
        ["java", "-Xmx2G", "-jar", "paper.jar", "nogui"],
        cwd=server, stdout=log_f, stderr=subprocess.STDOUT)
    print(f"[selftest] 服务器启动中（pid {proc.pid}），日志 {log_path}")

    start = time.time()
    while time.time() - start < DONE_TIMEOUT:
        if proc.poll() is not None:
            print(tail(log_path)[-3000:])
            sys.exit(f"[selftest] 服务器提前退出（code {proc.returncode}），见上方日志尾部")
        if "Done (" in tail(log_path):
            break
        time.sleep(2)
    else:
        proc.kill()
        print(tail(log_path)[-3000:])
        sys.exit(f"[selftest] {DONE_TIMEOUT}s 内没等到 Done，启动超时")
    print(f"[selftest] 服务器就绪（{time.time()-start:.0f}s）")

    # ---- 4. 跑自检：结果回给 RCON 发送端，流式收包直到汇总行 ----
    summary = ""
    failed_lines = []
    sock = None
    try:
        sock = socket_connect(args.rcon_port)
        # 认证：未认证会话发的命令会被服务端静默丢弃（此前空回包的根因）
        if not rcon.login(sock, args.password):
            print("[selftest] RCON 认证失败，密码不对？")
        else:
            text, done = rcon.run_streaming(
                sock, 21, "zhujian selftest",
                lambda t: "自检完成" in t, timeout=1800, idle_timeout=900)
            for line in text.splitlines():
                plain = strip_mc(line)
                if "自检完成" in plain:
                    summary = plain.strip()
                # 只认报告里的 ✗ 标记：检查项名字本身可能带「失败」二字，按关键字抓会误报
                if "✗" in plain and "自检完成" not in plain:
                    failed_lines.append(plain.strip())
            print("[selftest] RCON 输出已收齐" + ("" if done else "（超时，可能不完整）"))
            # 全量存档：失败项逐条核对靠它，不依赖控制台编码
            archive = os.path.join(server, "RESULT-selftest-"
                                   + os.path.basename(jar).replace(".jar", "") + ".txt")
            with open(archive, "w", encoding="utf-8") as af:
                af.write("\n".join(strip_mc(l) for l in text.splitlines()))
            print(f"[selftest] 完整输出存档：{archive}")
            print(f"[selftest] 汇总：{summary or '(没抓到汇总行)'}")
            for fl in failed_lines[:40]:
                print(f"[selftest] 失败项：{fl}")
            if not summary:
                print("[selftest] RCON 原始输出尾部：")
                print(strip_mc(text[-2000:]))
    finally:
        # ---- 5. 关服 ----
        if sock:
            if not args.keep:
                try:
                    rcon.run_command(sock, 99, "stop", settle_seconds=1.0)
                    print("[selftest] 已发 stop")
                except OSError:
                    pass
            sock.close()
        if not args.keep:
            try:
                proc.wait(timeout=60)
                print("[selftest] 服务器已退出")
            except subprocess.TimeoutExpired:
                proc.kill()
        log_f.close()
    return 0 if "失败 0" in summary or "失败：0" in summary else 1


def strip_mc(s: str) -> str:
    """去掉 Minecraft § 颜色码与 ANSI 转义，方便抓关键词。"""
    import re
    return re.sub(r"\u001b\[[0-9;]*m|§.", "", s)


def socket_connect(port: int):
    import socket as _s
    return _s.create_connection(("127.0.0.1", port), timeout=10)


if __name__ == "__main__":
    sys.exit(main())
