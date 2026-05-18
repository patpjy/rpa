# 保活白名单配置 — 群控部署清单

每台新手机加进群控前必须做这套配置,否则 dyrpa agent 会在 5-30 分钟内被 OEM 杀后台。

## 一键脚本(USB 接电脑,仅初始化用一次)

```bash
# 1. 装 APK
adb install apk/app/build/outputs/apk/release/app-release.apk

# 2. 跑保活脚本(自动批量处理所有 USB 连接的手机)
./scripts/keepalive_setup.sh
```

脚本会:
- 自动加 `com.dyrpa.agent` 到电池优化白名单(`dumpsys deviceidle`)
- 自动允许 `RUN_ANY_IN_BACKGROUND`(`appops`)
- 按 OEM 自动跳转启动管理页(用户手动勾选)

剩下的步骤(锁定最近任务卡片、Stellar 配对)必须手机上手动操作。

## 各 OEM 手动步骤详表

### 荣耀 / 华为 (MagicOS / EMUI)
1. 设置 → 应用 → 启动管理 → dyrpa agent → 关闭"自动管理"→ 勾全部三项
2. 设置 → 电池 → 启动应用管理 → dyrpa agent → 允许"自启动"
3. 设置 → 电池 → 更多电池设置 → 关闭"性能模式"对应用的限制
4. 最近任务上滑长按 dyrpa agent 卡片,出现锁图标
5. 设置 → 系统和更新 → 开发者选项 → 不锁定后台进程 (✓)

### 小米 / Redmi / POCO (MIUI / HyperOS)
1. 设置 → 应用 → 应用管理 → 应用启动管理 → dyrpa agent → 允许后台活动 (✓)
2. 设置 → 应用 → 应用管理 → dyrpa agent → 省电策略 → 无限制
3. 设置 → 应用 → 应用管理 → dyrpa agent → 允许通知 (✓)
4. 最近任务里下拉锁定卡片

### OPPO / OnePlus / Realme (ColorOS / OxygenOS)
1. 设置 → 应用管理 → 应用启动管理 → dyrpa agent → 允许自启动 + 允许关联启动
2. 设置 → 电池 → 应用耗电管理 → dyrpa agent → 允许完全后台行为 + 允许后台高耗电
3. 最近任务长按卡片 → 锁定

### vivo / iQOO (OriginOS / FuntouchOS)
1. 设置 → 电池 → 后台高耗电 → 允许 dyrpa agent
2. i 管家 → 应用管理 → 自启动管理 → 允许 dyrpa agent
3. 最近任务下拉锁定

### Samsung (OneUI)
1. 设置 → 设备维护 → 电池 → 应用电源管理 → 始终休眠 → 移除 dyrpa agent
2. 设置 → 应用 → dyrpa agent → 电池 → 允许后台活动 + 不优化
3. 最近任务长按图标 → 保持打开

### 原生 Android / Pixel
不需要任何 OEM 步骤。系统会通过 `dumpsys deviceidle whitelist` 直接生效。

## 验证清单

每台新机配置完成后:
- [ ] 锁屏 30 分钟,看 dashboard 心跳间隔 < 60s
- [ ] 拔 USB,只保留 SIM 卡蜂窝网络,确认心跳仍正常
- [ ] 飞行模式开关 1 次,确认 MQTT 自动重连 < 30s

如某台机心跳间隔 > 5 min,先检查最近任务里 dyrpa agent 卡片**是否被滑掉了**。
锁定后再观察 15 min。还有问题再回查上面 OEM 步骤。
