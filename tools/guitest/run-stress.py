#!/usr/bin/env python3
"""4 并发压测的一键跑手（计划任务 / 手动都走它）。

做四件事：
  1. 测试服没在跑就起它（paper.jar，日志到 testserver/_stress-server.log），等 "Done ("
  2. 部署 target/ 下最新的 ForgingMaster-*.jar（--no-deploy 可跳过）
  3. 跑 tools/guitest/concurrent4.js（默认 16 bot（max-players=20 的现实上限）；BOT_COUNT 可改，--phases 可只跑指定阶段）
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
SERVER = os.environ.get("FM_SERVER", "D:/game/1.21.10server")  # v2.26.5：旧 testserver 路径已失效（正斜杠 Windows 同样可用）
LOG = os.path.join(SERVER, "_stress-server.log")
PORT = 25565  # v2.26.5：随服务器实际位置更新
RCON_PORT = 25575  # v2.26.5：随服务器实际位置更新
RCON_PASS = "bmtest123"  # v2.26.5：随服务器实际位置更新

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


# 「装置人数不足」这一类 WARN 不是警告，是**没测到**。
# v2.26.0 踩过：32 bot 跑到 pass 阶段超时，summarize() 读了上一次 2 bot 的旧报告，
# 打印出「PASS 52 / WARN 4 / FAIL 0」—— 看起来是绿，实际四个阶段一个都没跑。
# 所以这类降级必须单独计数并让退出码非 0，避免「假绿」被写进交接报告。
SKIP_MARKERS = ("人数不足", "跳过", "跳过本阶段")


def is_skip_warn(row):
    if row.get("status") != "WARN":
        return False
    blob = (row.get("name") or "") + (row.get("detail") or "")
    return any(m in blob for m in SKIP_MARKERS)


def summarize(stamp, started_at=None, timed_out=False):
    """把 concurrent4.js 的 report 收成一行汇总 + FAIL 清单，存档。

    started_at：本次压测开始的时间戳。报告文件的 mtime 早于它就说明这份报告是
    **上一次跑剩下的**，绝不能拿来当本次结果（否则超时也会被读成通过）。
    """
    src = os.path.join(HERE, "concurrent4-report.json")
    if not os.path.exists(src):
        print("[stress] 没有 report 文件（脚本没跑到最后 / 被超时杀掉）")
        return None
    if started_at is not None and os.path.getmtime(src) < started_at - 1:
        print("[stress] report 文件是旧的（mtime 早于本次开始），不是本次结果 —— 按「没测到」处理")
        print("         旧报告：%s" % os.path.basename(src))
        return None
    with open(src, encoding="utf-8") as f:
        data = json.load(f)
    rows = data.get("rows", [])
    bad = [x for x in rows if x.get("status") == "FAIL"]
    warn = [x for x in rows if x.get("status") == "WARN"]
    skipped = [x for x in warn if is_skip_warn(x)]
    out = {
        "at": data.get("at"),
        "phases": data.get("phases"),
        # 硬规则要求「跑完核对 botCount」，汇总档里必须带出来（concurrent4-report.json 原生有）
        "botCount": data.get("botCount"),
        # 真正连上的数量：botCount 只是「打算跑几个」，连不上时它照样等于设定值
        "botCountAlive": data.get("botCountAlive"),
        # 每阶段实测耗时（毫秒，concurrent4.js 写的）：「单阶段占全量多少」以这里为准
        "phaseMs": data.get("phaseMs"),
        "timedOut": bool(timed_out),
        "pass": len(rows) - len(bad) - len(warn),
        "warn": len(warn),
        "fail": len(bad),
        # 「人数不足 → 降级」单独计数：它既不是通过也不是普通警告，是覆盖缺口
        "skipped": len(skipped),
        "fails": [{"area": x["area"], "name": x["name"], "detail": x.get("detail", "")} for x in bad],
        "warns": [{"area": x["area"], "name": x["name"], "detail": x.get("detail", "")} for x in warn],
        "skips": [{"area": x["area"], "name": x["name"], "detail": x.get("detail", "")} for x in skipped],
    }
    path = os.path.join(SERVER, "_stress-%s.json" % stamp)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("[stress] 汇总 PASS %d / WARN %d / FAIL %d / 降级未测 %d → %s"
          % (out["pass"], out["warn"], out["fail"], out["skipped"], os.path.basename(path)))
    if out["timedOut"]:
        print("  ✗ 本次压测超时被杀，下面的断言数是**跑到的部分**，不是全量")
    for x in out["fails"]:
        print("  ✗ [%s] %s — %s" % (x["area"], x["name"], x["detail"][:240]))
    for x in out["skips"]:
        print("  ⊘ [%s] %s — %s" % (x["area"], x["name"], x["detail"][:160]))
    for x in out["warns"]:
        if is_skip_warn(x):
            continue
        print("  ! [%s] %s — %s" % (x["area"], x["name"], x["detail"][:160]))
    # 硬规则：botCount 必须等于设定值，否则可能被 clamp 静默截断
    want = int(os.environ.get("BOT_COUNT", "16"))
    got = out["botCount"]
    alive = out.get("botCountAlive")
    if got != want:
        print("  ✗ botCount = %s，但本次设定 %d —— 被截断了，断言数不代表 %d 并发" % (got, want, want))
    elif alive is not None and alive != want:
        # botCount 只是「打算跑几个」。连不上时它照样是 32，所以必须单独看实际在线数。
        print("  ✗ 实际连上 %s/%d —— 有 bot 没进来，断言覆盖不足（这不是通过）" % (alive, want))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--phases", nargs="*", default=[])
    ap.add_argument("--key", action="store_true",
                    help="只跑关键阶段子集（约 17 分钟，全量约 33 分钟）")
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--no-deploy", action="store_true")
    ap.add_argument("--timeout", type=int, default=3600, help="压测本体超时（秒）")
    args = ap.parse_args()

    # v2.26.0：关键子集。全量 17 阶段 32 bot 要 33~53 分钟（_stress-20261113-115452 →
    # 132907 → 142216 三次实跑的时间戳差），是「改完代码快速验证」的最大成本。
    # 旧注释曾写「duelRank 一个就占 17.7 分钟、对 31 个 bot 各跑一遍」—— 查证不成立：
    # 该阶段的邀请链从落地起就是只对存活前两名跑 1 对，其余 bot 走档案/榜对账，
    # 而且当时没有任何阶段级计时记录（v2.26.1 起 concurrent4.js 每阶段打印实测耗时
    # 并写进报告 phaseMs，占多少分钟以数字为准，别再口口相传）。
    # 改代码后想快速确认「没把核心并发面改坏」，跑这 5 个就够：
    #   auction       同 tick 抢同一口价 → 只许一人成交
    #   bid           加价链 → 最高价唯一 + 被超的钱全退
    #   burst         10 条只读风暴 → 日志不许吐异常、人不许被踢
    #   spiritReroll  nonce 一次性 + 扣费守恒（本轮红过的两处之一）
    #   contract      三连 join 只许收一次钱（本轮红过的另一处）
    # 这些是**守恒律**断言，不看文案格式；剩下的阶段多是单点功能验证，
    # 改动没碰到的路径不必每轮全跑。
    # 注意：--key 不是「通过标准」的替代品。发版前仍应跑一次全量。
    if args.key and not args.phases:
        args.phases = ["auction", "bid", "burst", "spiritReroll", "contract"]
        print("[stress] --key：只跑关键子集 %s（约 17 分钟；发版前请跑全量）"
              % ", ".join(args.phases))

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

    # v2.26.5：真机世界可能跑在 Peaceful（本服实测就是）——敌对生物生成被服务端拒绝，
    # 秘境守卫全生成失败，tombrun 阶段只能测「退款」测不了「双花拒绝」。压测期间切
    # easy，跑完恢复原值；这些命令走 RCON，只在「服已就绪」之后执行。
    prev_difficulty = None
    sys.path.insert(0, os.path.join(REPO, "tools"))
    try:
        import rcon as _rcon
        _sock = socket.create_connection(("127.0.0.1", RCON_PORT), timeout=10)
        if _rcon.login(_sock, RCON_PASS):
            cur = _rcon.run_command(_sock, 1, "difficulty", settle_seconds=0.5) or ""
            if "Peaceful" in cur or "peaceful" in cur:
                prev_difficulty = "peaceful"
                _rcon.run_command(_sock, 2, "difficulty easy", settle_seconds=0.5)
                print("[stress] 检测到 Peaceful，压测期间临时切 easy（结束恢复）")
        _sock.close()
    except OSError as ex:
        print("[stress] 难度预检失败（继续，守卫类阶段可能测不到）：" + str(ex))

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    # 记下压测开始时刻：summarize() 用它识别「report 是上一次跑剩下的旧文件」。
    # 之前踩过：32 bot 超时被杀，summarize() 读了 2 bot 的旧报告，打出 PASS 52 / FAIL 0。
    started_at = time.time()
    env = dict(os.environ)
    env["NODE_PATH"] = os.path.join(HERE, "node_modules")
    env["FM_SERVER"] = SERVER
    # 并发数：默认 32（v2.25.7 实测结论）。
    # 为什么是 32：连接层不是瓶颈 —— 实测 5/6/8/12/24/32 全部 0 次握手节流，
    # burst 24 全成功，32 个上线 58s 且 20 秒后 32/32 存活。唯一硬墙是 max-players
    # （测试服已设 48）。断言数随人数线性长：5→63、12→112、24→196、32→252。
    # 上限定在 32 是因为一轮压测时长：**实测 32 并发跑满 17 个阶段约 33 分钟**
    # （v2.26.0 实测：11:23 起，到 pass 阶段已 29 分钟，1800s 超时当场被杀）。
    # 所以 --timeout 默认放到 3600s；写 17 分钟是旧数字，别再据此判断「卡住了」。
    env["BOT_COUNT"] = os.environ.get("BOT_COUNT", "16")
    cmd = ["node", "concurrent4.js"] + args.phases
    print("[stress] 跑：" + " ".join(cmd))
    timed_out = False
    try:
        r = subprocess.run(cmd, cwd=HERE, env=env, timeout=args.timeout)
        rc = r.returncode
    except subprocess.TimeoutExpired:
        print("[stress] 压测超时（%ds）—— 已跑到的断言仍会汇总，但这不是全量结果" % args.timeout)
        rc = 4
        timed_out = True

    out = summarize(stamp, started_at=started_at, timed_out=timed_out)

    if prev_difficulty:
        sys.path.insert(0, os.path.join(REPO, "tools"))
        try:
            import rcon as _rcon
            _sock = socket.create_connection(("127.0.0.1", RCON_PORT), timeout=10)
            if _rcon.login(_sock, RCON_PASS):
                _rcon.run_command(_sock, 3, "difficulty " + prev_difficulty, settle_seconds=0.5)
            _sock.close()
            print("[stress] 已恢复原难度：" + prev_difficulty)
        except OSError as ex:
            print("[stress] 恢复难度失败（记得手动 /difficulty peaceful）：" + str(ex))

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

    # 退出码语义（收紧过，别再只看 FAIL）：
    #   FAIL > 0            → 1  真失败
    #   降级未测 > 0        → 2  有阶段因人数不足没跑（覆盖缺口，不能当通过）
    #   botCount 不符设定   → 3  被截断
    #   超时                → 4
    #   旧报告 / 没报告     → 5  本次结果不可用
    if out is None:
        return 5
    if out["fail"] > 0:
        return 1
    if out["skipped"] > 0:
        return 2
    if out["botCount"] != int(os.environ.get("BOT_COUNT", "16")):
        return 3
    if out.get("botCountAlive") not in (None, int(os.environ.get("BOT_COUNT", "16"))):
        return 3
    if out["timedOut"]:
        return 4
    return rc


if __name__ == "__main__":
    sys.exit(main())