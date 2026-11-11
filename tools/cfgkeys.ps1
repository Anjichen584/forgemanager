# 配置键漂移核对：把 config.yml 的完整键路径与代码里读的键做机器比对
# 用法: pwsh -File tools\cfgkeys.ps1
param(
    [string]$Config = "src\main\resources\config.yml",
    [string]$SrcDir = "src\main\java"
)

$ErrorActionPreference = "Stop"

# --- 1) 解析 config.yml 的键路径（缩进感知） ---
$paths = New-Object 'System.Collections.Generic.HashSet[string]'
$stack = New-Object 'System.Collections.Generic.List[object]'   # 元素: @{indent; key}

foreach ($line in Get-Content -LiteralPath $Config) {
    if ($line -match '^\s*#' -or $line.Trim().Length -eq 0) { continue }
    if ($line -match '^(\s*)([A-Za-z0-9_.\-]+):\s*(.*)$') {
        $indent = $Matches[1].Length
        $key = $Matches[2]
        $rest = $Matches[3]
        # 去掉行尾注释。注意 `\s*` 已经把值前的空格吃掉，
        # 所以「纯注释值」（`key:   # 说明`）这里是直接以 # 开头，必须单独判定，
        # 否则会被当成有值 → 该段不压栈 → 整段子键全被判成缺失。
        if ($rest.TrimStart().StartsWith('#')) { $rest = '' }
        else { $rest = ($rest -replace '\s+#.*$', '') }

        while ($stack.Count -gt 0 -and $stack[$stack.Count - 1].indent -ge $indent) {
            $stack.RemoveAt($stack.Count - 1)
        }
        $prefix = ($stack | ForEach-Object { $_.key }) -join '.'
        $full = if ($prefix) { "$prefix.$key" } else { $key }
        [void]$paths.Add($full)

        # 值是空 => 这是一个段，后续缩进更深的是它的子键
        if ($rest.Trim().Length -eq 0) {
            $stack.Add([pscustomobject]@{ indent = $indent; key = $key })
        }
        # 行内映射 attack-damage: { multiplier: 1.25 } —— 把里面的键也当成子键
        elseif ($rest -match '^\s*\{(.*)\}\s*$') {
            $inner = $Matches[1]
            foreach ($pair in ($inner -split ',')) {
                if ($pair -match '^\s*([A-Za-z0-9_.\-]+)\s*:') {
                    [void]$paths.Add("$full.$($Matches[1])")
                }
            }
        }
    }
}

Write-Host ("config.yml 键路径: " + $paths.Count)

# --- 2) 抽出代码里字面量的读键调用 ---
$pat = '(?:settings\(\)\.(?:flag|intOf|num|str|strList|numList|longOf|section)|getConfig\(\)\.get(?:Boolean|Int|Double|Long|String|StringList|DoubleList|ConfigurationSection)|cfg\.get(?:Boolean|Int|Double|String|StringList)|getConfig\(\)\.get)\s*\(\s*"([A-Za-z0-9_.\-]+)"'
$codeKeys = @{}
foreach ($f in Get-ChildItem -Recurse -Filter *.java $SrcDir) {
    $text = Get-Content -Raw -LiteralPath $f.FullName
    foreach ($m in [regex]::Matches($text, $pat)) {
        $k = $m.Groups[1].Value
        if (-not $codeKeys.ContainsKey($k)) { $codeKeys[$k] = New-Object 'System.Collections.Generic.HashSet[string]' }
        [void]$codeKeys[$k].Add($f.Name)
    }
}
Write-Host ("代码字面量读键: " + $codeKeys.Count)

# --- 3) 比对：代码读但配置缺（且不是代码兜底的可选键） ---
$missing = @()
foreach ($k in ($codeKeys.Keys | Sort-Object)) {
    if (-not $paths.Contains($k)) {
        $missing += [pscustomobject]@{ key = $k; files = ($codeKeys[$k] | Sort-Object) -join ',' }
    }
}
Write-Host ("代码读但 config.yml 无此键: " + $missing.Count)
$missing | Format-Table -AutoSize -Wrap | Out-String -Width 200 | Write-Host

# --- 4) 反向：config.yml 有但代码从不读（疑似死键，仅供参考） ---
$unread = @()
foreach ($p in ($paths | Sort-Object)) {
    $used = $false
    foreach ($k in $codeKeys.Keys) {
        if ($k -eq $p -or $k.StartsWith("$p.") -or $p.StartsWith("$k.")) { $used = $true; break }
    }
    if (-not $used) { $unread += $p }
}
Write-Host ("config.yml 有但代码未直接读（含段/被程序化拼接的，需人工判断）: " + $unread.Count)

$missing | ForEach-Object { "$($_.key)`t$($_.files)" } | Set-Content -LiteralPath "audit\_missing-keys.txt" -Encoding UTF8
$unread | Set-Content -LiteralPath "audit\_unread-keys.txt" -Encoding UTF8
Write-Host "明细已写入 audit\_missing-keys.txt / audit\_unread-keys.txt"