# GUI bot 测试

用 mineflayer 起真 bot 连本地测试服，逐个打开界面扫每格材质，并实测点击链路。

```powershell
# 1. 测试服开着（testserver/，端口 25577，online-mode=false）
# 2. 给 bot 提权
powershell -File testserver/rcon.ps1 -Command "op TestBot"
# 3. 装依赖
cd tools/guitest; npm i mineflayer
# 4. 跑
node scan.js       # 扫全部界面的每一格
node deep.js       # 重铸/排行榜/宝石台的按钮实测
node chain.js      # 主菜单三个入口全链路
node forge.js      # 锻造五阶段
node stage.js      # 加热→锻打阶段推进
node fulltest.js   # 全功能实测：61 个子命令 + 满配物品链 + 附魔台闭环 + 15 个 GUI 契约
node durability-check.js  # 内置耐久真机对照：give 一把 -> 连击 -> 读 PDC 与 Lore 耐久行
node st-run.js     # 起一个 bot 在线后跑 /zhujian selftest，只打摘要与可疑行
node rq.js "list" "zhujian config check"   # 纯 RCON 发命令（不需要 bot）
```

`durability-check.js` 的三个坑（不处理的话会得出「耐久不掉」的**假结论**）：

- 测试服的出生点是个刷怪场，bot 站在那儿会被打死 —— 死一次整包东西掉光，读到的 `heldItem` 是 null。
  先把 bot `minecraft:tp` 到空地并切创造（**`/tp` 会被 CMI 接管，必须写 `minecraft:tp`**）。
- 打**普通僵尸**没用：满配剑两刀就把它打死，之后没有 damage 事件，耐久当然不动。
  要 `attribute @e[tag=...] minecraft:max_health base set 20000` 再 `data merge ... {Health:20000f}`。
- 创造模式不影响插件扣账（`EntityDamageByEntityEvent` 照常触发），只是原版耐久条本来就不动。

`fulltest.js` 是覆盖面最广的一把：跑完输出 `PASS/WARN/FAIL` 汇总，明细落 `fulltest-report.json`（机器可读）。
最近一次（v2.23.4 真机）：**PASS 114 / WARN 1 / FAIL 0**。

写新脚本前先读这三条教训（都是拿 14 条假失败换来的）：

1. **CMI 接管了 `/clear`**（要点击确认），`/give` `/gamemode` 同理可能被插件包装 —— 一律用
   命名空间原版命令：`/minecraft:clear`、`/minecraft:give`、`/minecraft:gamemode`。
2. **创造模式下 `/give` 不同步背包**（创造客户端对背包有权威，服务端给的物品会被吞）——
   物品链路全程用**生存模式**跑。
3. 发物品前先断言「背包已空」：背包满时 givefull 的产物会掉在脚下，`inventory.find()` 会捡到
   上一轮遗留的旧物品，断言全打在错的剑上。

判定规则：某槽位应当显示按钮，实际却是 `*_stained_glass_pane` 就是被填充板盖了。
`scan.js` 里的期望材质表是上一版写的，材质名要用小写（`anvil` 不是 `ANVIL`），比对前记得 toLowerCase。
