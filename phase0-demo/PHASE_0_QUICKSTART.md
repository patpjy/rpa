# Phase 0 快速通道 — 不用编译 APK

你的 Mac 没装 JDK / Android SDK / Docker。**与其装 30 分钟环境构 APK,不如用 F-Droid 现成开源 App 验证同样的两件事**:

| 要验证的能力 | 替代工具 | 等价于 |
|---|---|---|
| Shizuku 能通过 shell UID 注入 `input tap` | **LADB** (`com.draco.ladb`) | 我们 demo 里的 `Shizuku.newProcess("input tap ...")` |
| APK 前台服务保活 24h + 周期性 HTTP POST | **HTTP Shortcuts** (`ch.rmy.android.http_shortcuts`) | 我们 demo 里的 `HeartbeatService` |

Phase 2 真要写 APK 时再开 Gradle 工程,Phase 0 不需要走那一步。

---

## 准备(一次性,~30 min)

### 1. 装 Stellar(手机)
- 从 https://github.com/roro2239/Stellar/releases 下最新 APK,sideload
- 设置 → 关于手机 → 连点 7 次"版本号" → 开发者选项
- **打开"无线调试"**
- 打开 Stellar → "通过无线调试启动" → 输入系统弹的配对码
- Stellar 设置里勾 ✅ **开机启动 - Boot 广播** + ✅ **双进程互守**
- ❌ **不要勾"开机启动 - 无障碍"**(会把 Stellar 暴露到 `ENABLED_ACCESSIBILITY_SERVICES`)

### 2. 装 LADB(手机)
- F-Droid 应用 → 搜 "LADB" → 装
- 打开 LADB → 弹"需要 Shizuku 权限" → 去 Stellar 里允许 LADB
- 回到 LADB → 看到提示符 `$`,说明 shell UID 已就位

### 3. 装 HTTP Shortcuts(手机)
- F-Droid → 搜 "HTTP Shortcuts" → 装
- 暂不配置,等 server URL 确定

---

## 0.A 验证 Shizuku 能注入 input(3 min)

1. 打开开发者选项 → 勾上 **"显示点按操作反馈"**(看得到点击位置)
2. 打开抖音 App,任意页面
3. 切到 LADB,在提示符输入:
   ```
   input tap 500 500
   ```
4. 屏幕 (500, 500) 位置应该出现白色点击反馈圆点 → 抖音对应位置可能响应

**判定通过**: 看到白点 + 抖音有反应
**判定失败**: 报"Permission Denied" → Stellar/LADB 授权链没接上,回 Step 2 检查
**判定失败**: 无任何反应 → 可能开发者选项的"显示点按操作反馈"没开,或 LADB 没透过 Shizuku 而走了别的路

LADB 是 Shizuku 调 shell 的 wrapper,**只要 LADB 跑 `input tap` 成功,我们 Phase 2 自建 APK 直接调 Shizuku API 也能成功**。这步等价完成 Phase 0.4。

---

## 0.B 24h 保活测试

### 先决条件:可达的 heartbeat 服务器

手机用 HTTP Shortcuts 周期性 POST 到一个 URL。这个 URL 有三个选项:

| 方案 | 优 | 劣 | 适合 |
|---|---|---|---|
| **A. 本机 WiFi** | 零成本,5 分钟搞定 | 手机要连同一 WiFi,跟最终蜂窝部署不一致 | Phase 0 验证保活机制 |
| **B. ngrok 隧道** | 公网可达,跟蜂窝真实场景一致;免费 | 8h 后要重启隧道(免费版限制) | Phase 0 验证 + 模拟蜂窝 |
| **C. 你的 VPS** | 最真实,Phase 1 server 也部署在这 | 等你确定服务器地址 | 用户提到的"之后给地址" |

#### 方案 A (WiFi):本机起 server,手机连同一 WiFi 打过来

```bash
# 在你 Mac 上跑:
cd /Users/pat/Desktop/rpa/phase0-demo/server
python3 heartbeat_server.py 0.0.0.0 8080

# 看你 Mac 在 WiFi 里的 IP:
ipconfig getifaddr en0
# 假设输出 192.168.1.42,heartbeat URL 就是 http://192.168.1.42:8080/heartbeat
```

#### 方案 B (ngrok):本机起 server + ngrok 出公网

```bash
# 装 ngrok(brew install ngrok),注册免费账号拿 authtoken
ngrok config add-authtoken YOUR_TOKEN

# 一个终端:本机 server
python3 heartbeat_server.py 0.0.0.0 8080

# 另一个终端:开隧道
ngrok http 8080
# 看 "Forwarding" 行,heartbeat URL = https://xxxxx.ngrok-free.app/heartbeat
```

#### 方案 C (VPS):等你的服务器地址,把 server 跑那边

```bash
scp phase0-demo/server/heartbeat_server.py user@your.vps:/tmp/
ssh user@your.vps "python3 /tmp/heartbeat_server.py 0.0.0.0 8080"
# heartbeat URL = http://your.vps.ip:8080/heartbeat
```

### 配 HTTP Shortcuts

1. 打开 HTTP Shortcuts → 创建新 Shortcut
2. Method = `POST`
3. URL = 上面那个 heartbeat URL
4. Body = `{"device_id":"honor-test-01","ts":1000}` (Content-Type: `application/json`)
5. **关键**:打开 "Scripting" 标签,加 prepare script:
   ```javascript
   const body = {
     device_id: "honor-test-01",  // 给手机起个名
     ts: Math.floor(Date.now() / 1000),
     source: "phase0-test"
   };
   setRequestBody(JSON.stringify(body));
   ```
6. 保存 Shortcut
7. **设置周期触发**:HTTP Shortcuts → Triggers → Scheduled
   - Add Recurring → 每 **15 分钟**(HTTP Shortcuts 最小周期通常是 15min,这对保活验证够了)
   - 24h 期望 = 1440 / 15 = **96 条心跳**
   - 通过判定:**收到 ≥ 90 条 (>93%) + 任意两条间隔 < 1 小时**

### 荣耀保活配置(关键!不做这步保活必死)

每台荣耀都要做:

1. **应用启动管理**(设置 → 应用 → 启动管理)
   - HTTP Shortcuts → 关闭"自动管理"→ 勾 **自启动 + 关联启动 + 后台活动**
   - Stellar → 同上
   - LADB → 同上(它要常驻才能让 Shizuku 链路活着)
2. **电池优化忽略**(设置 → 电池 → 启动应用管理 或 电池优化)
   - HTTP Shortcuts、Stellar、LADB → 不优化
3. **最近任务卡片长按上滑锁定**
   - HTTP Shortcuts、Stellar、LADB → 全部锁定

### 跑测试

1. 锁屏放 24h
2. 期间可以正常用手机、打电话、随便切 WiFi/4G,不影响
3. 24h 后回来跑报告:

```bash
cd /Users/pat/Desktop/rpa/phase0-demo/server
python3 heartbeat_report.py hb.log --interval 900   # 15min = 900s
```

报告会输出每个设备的:
- 总收到 vs 期望
- 丢失率
- 最大间隔
- **PASS/FAIL** 判定

---

## Phase 0 完成判定卡

跑完 0.A 和 0.B,在 [phase0-demo/README.md](README.md) 末尾的"我的手机环境记录"里填上:

```
机型: 
Android 版本: 
MagicOS 版本: 
SIM 卡运营商: 

0.A Shizuku input tap: [ ] PASS / [ ] FAIL
0.B 24h 保活丢失率: ____ % (target < 7%)
0.B 最大间隔: ____ min (target < 60min)
```

任何一项 FAIL 就停下回来 — 可能需要换技术路线(比如 root + Magisk 比 Stellar 更靠谱)。

全 PASS → Phase 1 server 可以挂载到真实部署,Phase 2 APK 开始构建。
