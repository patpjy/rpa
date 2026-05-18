# Phase 0 Demo APK — Stellar/Shizuku + 心跳保活验证

这是 `plan-virtual-brooks.md` 里 Phase 0.4 + 0.5 的实操产物 — 一个最小 Android APK 验证两件事:

1. **Shizuku/Stellar 真的能借 shell UID 执行 `input tap`**(关键能力)
2. **APK 前台服务在荣耀 MagicOS 锁屏 24h 下心跳丢失率 < 1%**(关键可行性)

任何一项不过,**停下回来讨论**,不要进 Phase 1。

---

## 前置准备

- 一台**荣耀手机**(Android 11+ 优先,低于 11 要 USB pair 一次)
- 一台公网可达的 **Linux/macOS 服务器**(收心跳用,可以是 VPS、自家路由器、ngrok 出口都行)
- **Android Studio**(Mac 版 Hedgehog 2023.1+ 或 Iguana 2024.2+)
- 装好的 **Stellar APK**(从 https://github.com/roro2239/Stellar/releases 下载;**国内访问 GitHub 不通的话用代理或 ghproxy 镜像**)

---

## Step 1: 用 Android Studio 创建工程(10 min)

1. Android Studio → New Project → **Empty Views Activity**(不要 Compose,Kotlin 模板)
2. 填:
   - Name: `dyrpa-phase0`
   - Package name: `com.example.dyrpa`(后面正式 APK 再改成正经包名)
   - Language: Kotlin
   - Minimum SDK: **API 26 (Android 8.0)**
3. 创建后等 Gradle sync 完(国内首次同步可能 5-10 min,期间会下 Android Gradle Plugin)

**国内网络优化:** 编辑 `~/.gradle/init.d/init.gradle.kts`(没有就建),加阿里云镜像:

```kotlin
allprojects {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
    }
}
```

## Step 2: 拷贝本目录代码到工程(5 min)

| 本目录文件 | 拷到工程的位置 |
|---|---|
| `MainActivity.kt` | `app/src/main/java/com/example/dyrpa/MainActivity.kt`(覆盖) |
| `HeartbeatService.kt` | `app/src/main/java/com/example/dyrpa/HeartbeatService.kt`(新建) |
| `activity_main.xml` | `app/src/main/res/layout/activity_main.xml`(覆盖) |
| `AndroidManifest.xml` | `app/src/main/AndroidManifest.xml`(覆盖) |
| `app-build.gradle.kts` 里的 dependencies 块 | 合并到 `app/build.gradle.kts` |

## Step 3: 添加依赖(2 min)

把 `app-build.gradle.kts` 里的 dependencies 块合并到你工程的 `app/build.gradle.kts`,然后 Gradle Sync。

关键依赖:
- `dev.rikka.shizuku:api:13.1.5` — Shizuku/Stellar SDK
- `dev.rikka.shizuku:provider:13.1.5` — Provider 注册
- `com.squareup.okhttp3:okhttp:4.12.0` — 心跳 POST

如果 13.1.5 拉不到,去 https://central.sonatype.com/artifact/dev.rikka.shizuku/api 查最新版本号替换。

## Step 4: 装 Stellar + 一次性 pair(20 min)

1. 下载 Stellar APK 并 sideload 装到手机
2. 设置 → 关于手机 → 连续点 7 次"版本号" → 开发者选项打开
3. 开发者选项 → **打开"无线调试"**
4. 进 Stellar app → 选 "通过无线调试启动"
5. 系统弹"无线调试"配对窗,输入 Stellar 显示的配对码
6. 启动成功 — Stellar 显示"运行中"

### **关键!持久化路径选择**

Stellar 提供三种"开机启动"路径,**这是整个方案最大的风险点**:

| 路径 | 推荐 | 原因 |
|---|---|---|
| **Boot 广播** | ✅ **优先用** | 不暴露任何系统级标志,跟你 V9.1 现状一样的风险面 |
| **Root** | ⚠️ 次选 | 最稳,但 root 检测带来另一组风险(抖音明确检测) |
| **无障碍权限** | 🚨 **绝对不用** | 会让 Stellar 出现在 `ENABLED_ACCESSIBILITY_SERVICES`,我们最初要避开的就是这个 |

在 Stellar 设置里:
- ✅ 勾"开机启动 - Boot 广播"
- ✅ 勾"双进程互守"
- ❌ **不要**勾"开机启动 - 无障碍服务"
- ❌ **不要**勾 root 模式(除非你确认所有手机都已 root)

## Step 5: 加你的 APK 进荣耀保活白名单(5 min)

每台荣耀手机要做这套设置(MagicOS 后台杀进程极度激进):

1. **应用启动管理**(设置 → 应用 → 启动管理)
   - 找到 "dyrpa-phase0" → 关闭"自动管理"
   - 手动勾上:**自启动 / 关联启动 / 后台活动**(三项全勾)
   - Stellar 也同样设置

2. **电池优化忽略列表**(设置 → 电池 → 启动应用管理 或 电池优化)
   - dyrpa-phase0 → 不优化
   - Stellar → 不优化

3. **最近任务长按卡片上滑锁定**
   - 打开 dyrpa-phase0 → 进最近任务 → 长按卡片 → 上滑出现锁标志
   - Stellar 同样锁定

## Step 6: 部署心跳服务器(5 min)

在你公网服务器上:

```bash
# 假设你有 Python 3.8+
cd /opt/  # 或随便一个目录
git clone <你这个项目>  # 或者直接 scp server/ 上去
cd phase0-demo/server
python3 heartbeat_server.py 0.0.0.0 8080
```

或直接拷贝单文件:

```bash
scp server/heartbeat_server.py user@your.server:/tmp/
ssh user@your.server "python3 /tmp/heartbeat_server.py 0.0.0.0 8080"
```

服务器会监听 8080,每收到 POST 就追加到 `hb.log`(JSON 格式)。

**记得:** 服务器防火墙开 8080,或反代到 80/443 + TLS。

## Step 7: 跑端到端测试

1. APK 装上手机 → 打开 dyrpa-phase0
2. 点 "1. 请求 Shizuku 权限" → 在 Stellar 弹窗里点"允许"
3. 点 "2. 测试 input tap"
   - 在开发者选项里先开 "显示点按操作反馈"
   - 点按钮后应该看到屏幕 (500, 500) 出现一个白点 — 这就是 Shizuku 通过 shell UID 注入的真实触摸事件 ✅
   - 如果失败,看状态栏的错误信息(常见:Shizuku 没起、Stellar 配对掉了)
4. 在 "服务器 URL" 填 `http://your.server:8080/heartbeat`
5. 点 "3. 启动心跳" → 通知栏出现常驻通知
6. 锁屏放 24h(尽量正常用手机:正常断网、来电、上下班路上)

## Step 8: 24h 后看结果

服务器上:

```bash
cd /tmp  # 或心跳服务器所在目录
python3 /path/to/heartbeat_report.py hb.log
```

报告会输出:
- 总收到心跳数
- 丢失率 vs 理论值(1440 / 24h)
- 最大间隔
- 是否通过 Phase 0 判定(< 1% 丢失 + 最大间隔 < 5 min)

## Phase 0 完成判定清单

- [ ] Stellar 仓库验证通过(本项目里已经验证,你这边收到结果即可)
- [ ] 荣耀机型 + Android + MagicOS 版本记录在下方
- [ ] Stellar 重启 3 次后自动恢复(走 boot 广播,不走无障碍)
- [ ] demo APK 通过 Shizuku 成功 `input tap`
- [ ] 24h 保活丢失率 < 1%、最大间隔 < 5 min

### 我的手机环境记录

```
机型:
Android 版本:
MagicOS 版本:
SIM 卡运营商:
```

(填上之后我们就有判断后面 Phase 1-4 哪些细节要按你机型调的依据)

---

## 故障排查速查

| 症状 | 可能原因 | 处理 |
|---|---|---|
| Shizuku.requestPermission 抛 IllegalStateException | Stellar 没运行 | 进 Stellar app 重启服务 |
| Shizuku.newProcess 抛 RemoteException | Stellar binder 掉了 / 进程被杀 | 检查双进程互守是否启用,荣耀启动管理是否加了白名单 |
| input tap 没反应但 exitCode = 0 | 屏幕被其他 App 遮挡 | 确认抖音在前台,不在锁屏 |
| 心跳几小时后停 | 荣耀杀进程没规避 | 重新检查 Step 5 的三项设置 + 锁屏锁定 |
| Android Studio 拉依赖超时 | 国内 Maven 不通 | 配阿里云镜像(见 Step 1 末尾) |
