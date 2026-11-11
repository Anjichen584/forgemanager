# ============================================================================
#  botrun.ps1 —— 铸剑大师「bot 全都跑一遍」一键实测
#
#  用法（在仓库根目录）：
#     pwsh -File tools\botrun.ps1              # 完整：构建 + 数值实测 + 配置漂移
#     pwsh -File tools\botrun.ps1 -Fast        # 跳过 clean（只增量编译，改代码时快）
#     pwsh -File tools\botrun.ps1 -Server      # 额外尝试在本地 testserver 跑 /zhujian selftest
#
#  为什么默认一定带 clean：
#     target\ 里残留历次 jar，验证脚本按 mtime 取最新——不 clean 就会验到旧包，
#     出现「明明改了却没生效」。这条纪律写在 README「二十五、构建」。
#
#  退出码：0 = 全部通过；1 = 有失败项（可直接串进 CI）
# ============================================================================

param(
    [switch]$Fast,
    [switch]$Server,
    [string]$Maven = "D:\apache-maven-3.8.8\bin\mvn.cmd"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# 让中文在控制台正常显示（不改系统设置，只影响本次会话）
try { chcp 65001 > $null } catch { }
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$fail = 0
function Step($name) { Write-Host ""; Write-Host ("=" * 78); Write-Host "  $name"; Write-Host ("=" * 78) }
function Fail($msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red; $script:fail++ }
function Ok($msg)   { Write-Host "  [ OK ] $msg" -ForegroundColor Green }

# ---------------------------------------------------------------------------
Step "① 构建（$($Fast ? '增量' : 'clean 全量')）"
# ---------------------------------------------------------------------------
$targets = if ($Fast) { @("-B", "-q", "compile") } else { @("-B", "-DskipTests", "clean", "package") }
& $Maven @targets 2>&1 | Select-String -Pattern "BUILD|ERROR" | ForEach-Object { "  " + $_.Line }
if ($LASTEXITCODE -ne 0) {
    Fail "构建失败，后面不用跑了"
    exit 1
}
$jar = Get-ChildItem target\*.jar -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -eq $jar) { Fail "没找到 jar"; exit 1 }
Ok ("产物 {0}（{1:N0} 字节，{2:s}）" -f $jar.Name, $jar.Length, $jar.LastWriteTime)

# ---------------------------------------------------------------------------
Step "② 数值实测（bot 逐个跑：新手 → 中档 → 满配 → 极端）"
# ---------------------------------------------------------------------------
# 直接跑 target/classes 里的 NumericSim —— 它调的是线上同一份 DamageBudget / Crit
& java -cp target\classes com.forgemaster.tools.NumericSim
$simExit = $LASTEXITCODE
if ($simExit -eq 0) { Ok "数值断言全部通过" } else { Fail "数值有超标项（见上表 FAIL 行）" }

# ---------------------------------------------------------------------------
Step "③ 配置漂移（代码读、config.yml 里没有的键）"
# ---------------------------------------------------------------------------
if (Test-Path tools\cfgkeys.ps1) {
    & pwsh -NoProfile -File tools\cfgkeys.ps1 2>&1 | Select-Object -First 3 | ForEach-Object { "  " + $_ }
    $missingFile = "audit\_missing-keys.txt"
    if (Test-Path $missingFile) {
        # 动态前缀（键以 . 结尾，运行时拼档位号/品质名）不是真缺；
        # config-version 与 pose-version 是本插件自己写入的，也不算缺。
        $real = Get-Content $missingFile | Where-Object {
            if ($_ -notmatch '\.\t') { return $false }
            $key = ($_ -split "`t")[0]
            if ($key.EndsWith(".")) { return $false }
            if ($key -in @('config-version', 'anvil-forge.display.pose-version')) { return $false }
            return $true
        }
        if ($real) {
            $real | ForEach-Object { Fail "缺键：$_" }
        } else {
            Ok "无真实缺失（剩余为运行时写入键 / 动态前缀）"
        }
    }
} else {
    Write-Host "  (跳过：tools\cfgkeys.ps1 不存在)"
}

# ---------------------------------------------------------------------------
Step "④ 服务端自检（/zhujian selftest）"
# ---------------------------------------------------------------------------
$serverJar = Get-ChildItem -Path . -Filter "paper*.jar" -Recurse -Depth 2 -ErrorAction SilentlyContinue |
    Sort-Object Length -Descending | Select-Object -First 1
if ($Server) {
    if ($null -eq $serverJar) {
        Fail "没找到 paper*.jar —— 先把服务端 jar 放到仓库根目录或 testserver\"
    } else {
        Write-Host "  找到服务端：$($serverJar.FullName)"
        Write-Host "  ⚠ 本脚本不自动启服（启服 = 开端口 + 写世界，属于服主决定的事）。"
        Write-Host "     请手动执行："
        Write-Host "       1) 把 $($jar.Name) 拷进 testserver\plugins\（覆盖旧的，别留两个）"
        Write-Host "       2) 启动服务端"
        Write-Host "       3) 控制台输入：zhujian selftest     （注意：控制台不带斜杠）"
        Write-Host "       4) 全绿再上生产"
        $fail++
    }
} else {
    Write-Host "  (未指定 -Server) 需要进服验证时加 -Server 参数。"
    Write-Host "  提醒：selftest 覆盖 100+ 断言，含本轮新增的「属性膨胀幂等 / 脏快照收敛 /"
    Write-Host "        八类 Webhook 事件 id / 战力分钳位」——上生产前务必跑一次。"
}

# ---------------------------------------------------------------------------
Step "汇总"
# ---------------------------------------------------------------------------
if ($fail -eq 0) {
    Write-Host "  全部通过。可以按 README 的四步流程部署。" -ForegroundColor Green
} else {
    Write-Host "  有 $fail 项问题，先修再部署。" -ForegroundColor Red
}
exit ($fail -eq 0 ? 0 : 1)