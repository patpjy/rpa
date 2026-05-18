# Shizuku 重连 SOP

> 2026-05-18 实证版。当 `shizuku_server` 死了(`ps -A` 找不到),按这份走 30 秒内恢复。

---

## 什么时候用这份

任一症状命中:
- `adb shell ps -A | grep shizuku_server` **无输出**(只有 `moe.shizuku.privileged.api` 是 manager app,不算)
- dyrpa-agent 报 "Shizuku 未运行" / "权限未授予"
- workflow 全部步骤秒失败,日志 `Shizuku.newProcess() unavailable`
- APK 刚通过 **USB** reinstall 完(USB transport 状态变化触发 adbd 清理 shell-UID 子进程;**纯 TCP install 不会杀 shizuku**,2026-05-18 honor50-02 实证)

---

## 关键不变量(读完再操作)

**HONOR + Android 10 + 非 root**:
- USB 插着启的 shizuku → 后续任何 USB 事件 / 拔线 100% 必死,setsid/nohup 救不了
- **必须先拔 USB 让 adbd 进 TCP-only 稳态,再启 shizuku**
- 起完之后 USB 别再插了 — 蜂窝/WiFi/电源都不影响,只要不重启手机就持续运行

(原理:adbd 重启会清理 shell-UID 子进程。shizuku 在 USB-存在时刻出生 → adbd 把它登记在清理钩子里 → 拔 USB 触发 adbd 重启 → shizuku 被收走。先拔 USB → adbd 进无 USB 状态 → 此时启 shizuku → 钩子里没它 → 后续 USB 怎么动都无所谓。)

---

## SOP

### 0. 看状态(确认要走这份 SOP)

```bash
adb devices -l
adb -s 10.1.2.178:5555 shell 'ps -A | grep shizuku' 2>&1
```

预期:看到 `moe.shizuku.privileged.api`(manager,正常)但**没有 `shizuku_server`**(daemon,死了)。

### 1. 你拔 USB(如果还插着)

`adb devices -l` 里如果看到 `usb:0-1.X` 行,人肉拔掉。等 3-5 秒让 adbd 进 TCP-only 稳态。

再 `adb devices -l` 确认**只剩 `10.1.2.178:5555`** 一行。

### 2. 拿到 Shizuku APK 当前装的路径

(每次 reinstall 后面那串 hash 会变,不能写死。)

```bash
adb -s 10.1.2.178:5555 shell 'pm path moe.shizuku.privileged.api'
# 输出形如: package:/data/app/moe.shizuku.privileged.api-Va26HOuIkQuI6vVB-HrFrw==/base.apk
```

取出 `/data/app/.../` 部分。

### 3. 起 shizuku_server

直接跑 APK 里的 native binary `libshizuku.so`(Rikka 13.6 自带 starter 走这条):

```bash
APK_DIR=$(adb -s 10.1.2.178:5555 shell 'pm path moe.shizuku.privileged.api' | sed 's/package://;s|/base.apk||' | tr -d '\r')
adb -s 10.1.2.178:5555 shell "$APK_DIR/lib/arm64/libshizuku.so"
```

预期输出:
```
info: starter begin
info: killing old process...
info: apk path is /data/app/moe.shizuku.privileged.api-.../base.apk
info: starting server...
info: shizuku_server pid is <PID>
info: shizuku_starter exit with 0
```

### 4. 验活

```bash
adb -s 10.1.2.178:5555 shell 'ps -A -o PID,PPID,USER,ARGS | grep shizuku_server | grep -v grep'
```

预期单行:
```
<PID>     1 shell        shizuku_server
```

三个关键点:
- **PPID = 1** → 已脱离 adb shell,daemonized
- **USER = shell** → shell UID,Shizuku.newProcess() 可以正常 binder IPC
- **进程名 shizuku_server** → 是真守护,不是 manager app

### 5. 手机端重启 dyrpa-agent

(APK reinstall 或 shizuku 死了之后,前台服务也得手动拉。)

1. 打开 **dyrpa-agent** app
2. 点 **启动**
3. 看 dashboard `http://localhost:8888/devices/honor50-01` 出现 heartbeat,状态 = online

### 6. 跑一次烟雾测试

dashboard 启动 max_items=1 的关键词搜索,确认 `tap_xy` → `input_text` → 进搜索结果页全链路通。

---

## 故障

| 现象 | 诊断 |
|---|---|
| step 3 报 `Aborted` | APK 路径错了 / 不是 Rikka 13.6 原版。重新 `pm path` 拿路径 |
| step 4 看到 `shizuku_server` 但 PPID 不是 1 | 起的时候 adb shell 还没断开,没 daemonize 干净。换 step 3 用 `&` 后台跑 |
| 起完 5 分钟内又死 | 你 step 1 没拔干净,USB 还插着 / 起完又插了一下。重做整套 SOP |
| dyrpa-agent 启动后还是连不上 Shizuku | manager app 里"已授权应用"看 dyrpa 有没有 grant,没的话点同意 |
| `pm path` 返回空 | Shizuku 没装。`adb install shizuku-v13.6.x.apk` 重新装 |

---

## 装机首次 bootstrap(不是本 SOP 范围)

完整初装走 `README.md` 的 "单机手机 bootstrap SOP" 段(README:100)。本 SOP 只覆盖**已经装好 + 已经成功跑过一次后** shizuku 意外死掉的恢复路径。

---

## 引用

- [`README.md`](README.md) "Shizuku 持久化关键"(L65-)— 为什么必须先拔 USB
- memory `project_shizuku_state.md` — Rikka 13.6 HONOR 实情
