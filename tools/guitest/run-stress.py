#!/usr/bin/env python3
"""4 并发压测的一键跑手（计划任务 / 手动都走它）。

做四件事：
  1. 测试服没在跑就起它（paper.jar，日志到 testserver/_stress-server.log），等 "Done ("
  2. 部署 target/ 下最新的 ForgingMaster-*.jar（--no-deploy 可跳过）
  3. 跑 tools/guitest/concurrent4.js（默认 5 bot：ConcA..ConcE；BOT_COUNT 可改，--phases 可只跑指定阶段）
  4. 汇总存档到 testserver/_stress-<时间戳>.json，stdout 打印 PASS/WARN/FAIL；自起的服跑完关掉

用法：
  python tools/guitest/run-stress.py                       # 全阶段
  python tools/guitest/run-stress.py --phases signin bid   # 只跑指定阶段
  python tools/guitest/run-stress.py --keep                # 跑完不关服
  python tools/guitest/run-stress.py --no-deploy           # 不重新部署 jar

环境变量：FM_SERVER 覆盖测试服目录（默认 D:\\IDEA\\mc-plugins\\ForgingMaster\\testserver）。

为什么固定 4 并发：10 个 bot 同时握手会撞连接限流（429 / 排队超时），
而 4 个已经在同一 tick 抢同一份账本 —— 并发面已经足够，不必加人。
"""
import argparse
import datetime
import glob
import json
import os
import socket
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
SERVER = os.environ.get("FM_SERVER", r"D:\IDEA\mc-plugins\ForgingMaster\testserver")
LOG = os.path.join(SERVER, "_stress-server.log")
PORT = 25577
RCON_PORT = 25599
RCON_PASS = "testpass"

# Windows 控制台默认 cp936，输出被管道接走时中文会变成乱码；强制 UTF-8
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def port_open(port, host="127.0.0.1", timeout=1.0):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def tail(limit=8000):
    try:
        with open(LOG, encoding="utf-8", errors="replace") as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            f.seek(max(0, size - limit))
            return f.read()
    except OSError:
        return ""


def wait_ready(proc, timeout=300):
    start = time.time()
    while time.time() - start < timeout:
        if proc is not None and proc.poll() is not None:
            return False
        if port_open(PORT) and "Done (" in tail():
            return True
        time.sleep(2)
    return False


def deploy():
    jars = sorted(glob.glob(os.path.join(REPO, "target", "ForgingMaster-*.jar")), key=os.path.getmtime)
    if not jars:
        print("[stress] target/ 里没有 jar，跳过部署")
        return None
    jar = jars[-1]
    target_dir = os.path.join(SERVER, "plugins")
    os.makedirs(target_dir, exist_ok=True)
    for old in glob.glob(os.path.join(target_dir, "ForgingMaster-*.jar")):
        os.remove(old)  # 只留一个版本，避免同名插件打架
    shutil.copy2(jar, os.path.join(target_dir, os.path.basename(jar)))
    print("[stress] 部署 " + os.path.basename(jar))
    return jar


def stop_server():
    sys.path.insert(0, os.path.join(REPO, "tools"))
    try:
        import rcon
    except ImportError:
        return False
    try:
        sock = socket.create_connection(("127.0.0.1", RCON_PORT), timeout=10)
    except OSError:
        return False
    try:
        if rcon.login(sock, RCON_PASS):
            rcon.run_command(sock, 99, "stop", settle_seconds=1.0)
            return True
    finally:
        sock.close()
    return False


def summarize(stamp):
    """把 concurrent4.js 的 report 收成一行汇总 + FAIL 清单，存档。"""
    src = os.path.join(HERE, "concurrent4-report.json")
    if not os.path.exists(src):
        print("[stress] 没有 report 文件（脚本可能没跑到最后）")
        return None
    with open(src, encoding="utf-8") as f:
        data = json.load(f)
    rows = data.get("rows", [])
    bad = [x for x in rows if x.get("status") == "FAIL"]
    warn = [x for x in rows if x.get("status") == "WARN"]
    out = {
        "at": data.get("at"),
        "phases": data.get("phases"),
        "pass": len(rows) - len(bad) - len(warn),
        "warn": len(warn),
        "fail": len(bad),
        "fails": [{"area": x["area"], "name": x["name"], "detail": x.get("detail", "")} for x in bad],
        "warns": [{"area": x["area"], "name": x["name"], "detail": x.get("detail", "")} for x in warn],
    }
    path = os.path.join(SERVER, "_stress-%s.json" % stamp)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("[stress] 汇总 PASS %d / WARN %d / FAIL %d → %s"
          % (out["pass"], out["warn"], out["fail"], os.path.basename(path)))
    for x in out["fails"]:
        print("  ✗ [%s] %s — %s" % (x["area"], x["name"], x["detail"][:240]))
    for x in out["warns"]:
        print("  ! [%s] %s — %s" % (x["area"], x["name"], x["detail"][:160]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--phases", nargs="*", default=[])
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--no-deploy", action="store_true")
    ap.add_argument("--timeout", type=int, default=1800, help="压测本体超时（秒）")
    args = ap.parse_args()

    if not os.path.isdir(SERVER):
        print("[stress] 找不到测试服目录：%s（用 FM_SERVER 指过去）" % SERVER)
        return 2

    started_here = False
    proc = None
    logf = None
    if port_open(PORT):
        print("[stress] 测试服已在运行，直接测")
    else:
        if not args.no_deploy:
            deploy()
        logf = open(LOG, "wb")
        proc = subprocess.Popen(["java", "-Xmx2G", "-jar", "paper.jar", "nogui"],
                                cwd=SERVER, stdout=logf, stderr=subprocess.STDOUT)
        started_here = True
        print("[stress] 起服 pid %d" % proc.pid)
        if not wait_ready(proc):
            print("[stress] 起服失败，尾部日志：")
            print(tail(2000))
            try:
                proc.kill()
            except OSError:
                pass
            return 3
        print("[stress] 服已就绪")

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    env = dict(os.environ)
    env["NODE_PATH"] = os.path.join(HERE, "node_modules")
    env["FM_SERVER"] = SERVER
    # 并发数：默认 5（v2.25.7 实测 5/6/8 都能稳定上线，0 次握手节流）。
    # 为什么不是越多越好：并发面靠「多人同时抢同一份资源」来暴露竞态，
    # 5 个已经能覆盖全部断言面（56→63 条），再往上只增加起服时间，不增加覆盖率。
    env["BOT_COUNT"] = os.environ.get("BOT_COUNT", "5")
    cmd = ["node", "concurrent4.js"] + args.phases
    print("[stress] 跑：" + " ".join(cmd))
    try:
        r = subprocess.run(cmd, cwd=HERE, env=env, timeout=args.timeout)
        rc = r.returncode
    except subprocess.TimeoutExpired:
        print("[stress] 压测超时（%ds）" % args.timeout)
        rc = 4

    out = summarize(stamp)

    if started_here and not args.keep:
        stop_server()
        if proc is not None:
            try:
                proc.wait(timeout=90)
            except subprocess.TimeoutExpired:
                proc.kill()
        if logf:
            logf.close()
        print("[stress] 服已关闭")

    if out is not None and out["fail"] > 0:
        return 1
    return rc


if __name__ == "__main__":
    sys.exit(main())