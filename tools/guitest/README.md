# GUI bot 测试

用 mineflayer 起真 bot 连本地测试服，逐个打开界面扫每格材质，并实测点击链路。

```powershell
# 1. 测试服开着（testserver/，端口 25577，online-mode=false）
# 2. 给 bot 提权
powershell -File testserver/rcon.ps1 -Command "op TestBot"
# 3. 装依赖
cd tools/guitest; npm i mineflayer
# 4. 跑
node scan.js     # 扫全部界面的每一格
node deep.js     # 重铸/排行榜/宝石台的按钮实测
node chain.js    # 主菜单三个入口全链路
node forge.js    # 锻造五阶段
node stage.js    # 加热→锻打阶段推进
```

判定规则：某槽位应当显示按钮，实际却是 `*_stained_glass_pane` 就是被填充板盖了。
`scan.js` 里的期望材质表是上一版写的，材质名要用小写（`anvil` 不是 `ANVIL`），比对前记得 toLowerCase。
