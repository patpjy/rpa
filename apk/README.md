# dyrpa agent (Android APK · Phase 2)

实现"无电脑、无 WiFi 链路、纯蜂窝接服务器"的设备端 agent。所有点击/滑动/UI 查询都通过 **Shizuku/Stellar 借 shell UID** 执行,不走无障碍服务。

## 当前状态

Phase 2 shipped:
- ✅ MQTT 长连接(Eclipse Paho) — 自动重连、QoS 1
- ✅ 前台 Service + WakeLock + 周期心跳
- ✅ Shizuku ShellExecutor 包装 input/uiautomator/screencap/am
- ✅ 工作流解释器(对应 V9.1 `execute_steps`,带 SkipCandidate 恢复)
- ✅ Tier 1 actions(5 个): `tap_xy` `tap_text` `tap_desc` `press` `wait`

Phase 3 待加(10 个 actions): `tap_resource_id` `input_text` `swipe` `screenshot` `dump_hierarchy` `collect_visible_text` `tap_image`(OpenCV) `image_exists` `tap_xy_if_missing` `tap_relative_to_element` + `skip_if_not_exists` `skip_if_exists`

## 项目结构

```
apk/
├── settings.gradle.kts
├── build.gradle.kts          ← project-level
├── gradle.properties
└── app/
    ├── build.gradle.kts      ← module-level + deps
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/{layout,values}
        └── java/com/dyrpa/agent/
            ├── MainActivity.kt           ← 一次性配置 broker + device_id
            ├── service/AgentService.kt   ← 前台 service + 心跳 + MQTT 派发
            ├── mqtt/MqttClient.kt        ← Paho 客户端(收 task/control,发 hb/event)
            ├── shizuku/ShellExecutor.kt  ← 所有 shell 命令的唯一入口
            ├── workflow/
            │   ├── WorkflowModel.kt      ← TaskMessage + Step
            │   └── Interpreter.kt        ← V9.1 流程逻辑(open_search/submit/per_item + 恢复)
            ├── actions/
            │   ├── Action.kt             ← 接口 + ActionRegistry
            │   ├── TapXy.kt
            │   ├── TapText.kt
            │   ├── TapDesc.kt
            │   ├── Press.kt
            │   └── Wait.kt
            └── util/
                ├── DeviceInfo.kt         ← 心跳载荷(电池/IP/型号)
                ├── ScreenSize.kt
                └── UiTreeFinder.kt       ← 解析 uiautomator dump XML
```

## MQTT 协议(跟服务器对齐)

**收(server → device):**
- `dyrpa/devices/{id}/task` — 任务下发,见 `WorkflowModel.TaskMessage.parse()`
- `dyrpa/devices/{id}/control` — 控制命令(目前只有 `{"cmd":"stop"}`)

**发(device → server):**
- `dyrpa/devices/{id}/heartbeat`(retained,每 45s):`DeviceInfo.collect()` 的 JSON
- `dyrpa/devices/{id}/event`(QoS 1):`{type, task_id, ts, data}`
  - type ∈ `log` / `task_started` / `step_complete` / `task_done` / `task_failed`

## 怎么构建(用户当前环境)

你 Mac 上还没装 Android Studio / JDK。代码已就绪,等你装了 AS 就能直接打开 `apk/` 文件夹 import 工程。两个路径:

### A. Android Studio(推荐)
1. 装 [Android Studio Iguana+](https://developer.android.com/studio)
2. File → Open → 选 `apk/`
3. 等 Gradle Sync (国内首次 5-10 min,加阿里云镜像见 `settings.gradle.kts`)
4. Run → 装到手机(USB 或无线调试连接)

### B. 命令行 Gradle(如果只想 build APK 不写代码)
```bash
# 装 JDK 17 + Android command-line tools
brew install --cask temurin@17
brew install --cask android-commandlinetools
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

cd apk
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk

adb install app/build/outputs/apk/debug/app-debug.apk
```

## 跟服务器联调

1. 启动 server(见 `../server/README.md`)
2. 启动 MQTT broker(`mosquitto` 或 EMQX)
3. APK 里填:
   - broker = `tcp://你的服务器IP:1883`
   - device_id = 任意字符串(如 `honor-01`)
4. 服务器控制台 `/devices/honor-01` 下发 workflow

## V9.1 → Phase 2 移植映射

| V9.1 (Python) | Phase 2 (Kotlin) | shell 调用 |
|---|---|---|
| `device.click()` | `actions/TapXy.kt` | `input tap X Y` |
| `device.click_text()` | `actions/TapText.kt` | `uiautomator dump` → parse → `input tap` |
| `device.click_desc()` | `actions/TapDesc.kt` | 同上,attr=content-desc |
| `device.press(key)` | `actions/Press.kt` | `input keyevent KEYCODE_X` |
| `wait` step | `actions/Wait.kt` | (sleep with stop check) |
| `execute_steps()` | `workflow/Interpreter.kt` | (纯控制流) |
| `SkipCandidate` 恢复 | 同名异常 + back×3 + swipe | `input keyevent` × 3 + `input swipe` |
