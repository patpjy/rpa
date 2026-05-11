# 抖音 RPA 控制台

> Mac 通过 USB 控制安卓手机自动跑抖音工作流：搜关键词 → 进视频 → 关注 → 发私信 → 滑下一条。带 Web 控制台、多工作流、实时日志、截图回放、失败自动 dump。

---

## 1. 是什么

```
┌──────────┐        HTTP/SSE        ┌──────────┐         USB+ADB         ┌──────────┐
│ 浏览器    │ ──────────────────────→│ Mac 后台  │ ───────────────────────→│ 安卓手机  │
│ 控制台    │←── 实时日志 / 截图 ────│ Flask+   │←── UI dump / screencap ─│ 抖音 App  │
└──────────┘                        │ Python   │                          └──────────┘
                                    └──────────┘
```

| 文件 / 目录 | 作用 |
|------|------|
| `rpa_mvp.py` | RPA 引擎，按 yaml 描述执行 ADB / uiautomator2 动作 |
| `dashboard.py` | Flask Web 控制台（端口 8003，SSE 推日志） |
| `workflows/*.yaml` | 工作流定义，一个文件一个独立场景 |
| `workflows/_template.yaml` | 新工作流模板（下划线前缀不会出现在下拉菜单） |
| `templates/index.html` + `static/dashboard.{css,js}` | 控制台前端，纯 HTML/JS 无框架 |
| `assets/` | 图像匹配模板（`tap_image` 用，例如 `follow_plus.png`） |
| `backups/` | 历代版本快照（`v8-baseline` / `v8-with-faildump` / `v9-resourceid`） |
| `outputs/run_*/` | 每次运行的截图 / UI dump / 发送记录（gitignore，不公开） |

---

## 2. 快速开始

### 2.1 Mac 端

```bash
git clone https://github.com/patpjy/rpa.git
cd rpa
brew install android-platform-tools scrcpy
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 2.2 手机端（HUAWEI / 荣耀示例）

1. 设置 → 关于手机 → 连续点版本号 7 次 → 进开发者模式
2. 系统和更新 → 开发人员选项 → 打开：
   - ✅ **USB 调试**
   - ✅ **仅充电模式下允许 ADB 调试**
   - ✅ **USB 安装**
3. 插 USB → 手机端选「**传输文件**」（不能只是充电）
4. 弹「允许 USB 调试」→ 勾「始终允许」→ 允许

其他品牌路径见 [§7](#7-手机端配置其他品牌)。

### 2.3 验证连接

```bash
adb devices
# 应该看到一行 <序列号>  device
```

### 2.4 启动控制台

```bash
.venv/bin/python dashboard.py
```

浏览器打开 **http://127.0.0.1:8003** 就能用了。控制台只绑回环地址，不对外暴露。

---

## 3. 控制台用法

```
┌──────────────────────┐  ┌─────────────────────────────┐
│ 设备状态              │  │ 实时日志（SSE 推流）          │
│  ● 序列号 / 厂商      │  │ [13:03:20] screenshot ...   │
│  IP / MAC / 电量      │  │ [13:03:21] tap desc='关注'  │
│  分辨率 / 前台 / 屏幕 │  │ ...                         │
├──────────────────────┤  │                             │
│ 任务进度  2/2  ▰▰▰▰  │  │                             │
├──────────────────────┤  │                             │
│ 控制                  │  │                             │
│  工作流 [下拉 ▾]      │  │                             │
│  候选数 [_]  关键词 [_]│  │                             │
│  私信话术 [文本框]    │  │                             │
│  [▶ inspect] [▶ 启动] │  │                             │
│  [■ 停止]             │  │                             │
└──────────────────────┘  └─────────────────────────────┘
┌────────────────────────────────────────────────────────┐
│ 最新截图（运行中实时刷新）                              │
└────────────────────────────────────────────────────────┘
```

**操作流程**：
1. 浏览器打开 http://127.0.0.1:8003
2. 设备状态显示 🟢 + 序列号 = 连接 OK
3. 选工作流（默认「抖音私信」v9）
4. 候选数 / 关键词 / 私信话术随时改 —— 三个字段自动存 `localStorage`，关浏览器换设备都不丢
5. **手机不用回首页**，工作流会自己 force-stop + monkey 冷启抖音
6. 点 **▶ 启动 workflow**
7. 看右侧日志和下方截图，跑完进度条满

**inspect 按钮**：只截图 + 抓 UI 树（输出到 `outputs/inspect_<ts>/`），**不点任何东西**。调试新关键词、新 UI 时用。

**私信话术输入框**：留空 = 用 yaml `drafts.dm_template` 默认值；非空 = 覆盖。话术里的 `{keyword}` 占位符会被实际关键词替换。

**停止按钮**：每个 action 开头和长 `wait` 内部都会查 stop 标志，<1s 内生效。

---

## 4. 改 / 加工作流

### 4.1 改现有工作流

直接编辑 `workflows/douyin-dm.yaml`。改完**刷新浏览器**生效（不用重启后端）。

关键字段：
```yaml
meta:
  name: "抖音私信"                 # 控制台下拉显示的名字
  description: "..."             # 鼠标悬停或控制台描述
workflow:
  keyword: "北京海淀二手房"        # 默认关键词（可被 dashboard 覆盖）
  max_items: 3                  # 默认候选数（可被覆盖）
  wait_after_action: 0.3        # 每步默认等待秒数
  open_search: [...]            # 从首页到搜索页的动作（执行一次）
  submit_search: [...]          # 提交搜索 + 进第一个视频（执行一次）
  per_item: [...]               # 每个候选博主的完整闭环（执行 max_items 次）
drafts:
  dm_template: "您好..."        # 固定话术（支持 {keyword} 占位符）
```

### 4.2 加新工作流

```bash
cp workflows/_template.yaml workflows/<新工作流-id>.yaml
# 编辑文件，改 meta / keyword / per_item / dm_template
```

刷新浏览器 → 下拉里出现新工作流。详见 [`workflows/README.md`](workflows/README.md)。

### 4.3 工作流的动作 DSL

引擎共支持 16 个 action。完整速查见 `workflows/_template.yaml` 底部，常用如下：

**点击类**

| action | 关键参数 | 说明 |
|--------|---------|------|
| `tap_text` | `value: "搜索"` `clickable: true` | text 匹配，找到后用 `adb input tap` 打中心 |
| `tap_desc` | `value: "关注"` `clickable: true` | content-desc 匹配（找按钮首选） |
| `tap_resource_id` | `value: "com.ss.android.ugc.aweme:id/user_avatar"` | Android resource-id 匹配。比 `tap_xy` 多 ~200ms 但**抗布局漂移**（视频带挂件 / 合集时右侧栏整体上移，绝对像素必落空） |
| `tap_xy` | `x: 0.5` `y: 0.3` | 比例坐标（0-1） |
| `tap_xy_if_missing` | `text` 或 `desc` + `x` `y` | 元素不存在才落坐标 —— 二选一兜底 |
| `tap_relative_to_element` | `anchor_text` 或 `anchor_desc` + `dx` `dy` | 找锚点元素后偏移 N 像素 tap —— 抖音 SurfaceView 头像兜底 |
| `tap_image` | `template: assets/x.png` `threshold: 0.8` | 多尺度模板匹配 + 随机抖动 |

**输入 / 系统**

| action | 关键参数 | 说明 |
|--------|---------|------|
| `input_text` | `value: "..."` 或 `value_from: keyword/dm_template` | 用 ADBKeyboard 支持中文 |
| `press` | `key: back/enter/home` | 系统键 |
| `swipe` | `start: [0.5, 0.8]` `end: [0.5, 0.2]` `duration: 0.4` | 比例滑动 |
| `wait` | `seconds: 1.5` | 死等。短切片循环，长 wait 也能秒停 |

**采集 / 控制流**

| action | 关键参数 | 说明 |
|--------|---------|------|
| `screenshot` | `name: first_video` | 存截图到 `outputs/run_*/` |
| `collect_visible_text` | `name: ui` | 抓 UI 树 + 提取 `text` / `content-desc` / `resource-id` 文本，落 xml |
| `skip_if_not_exists` | `text` / `desc` / `template` + `reason` | 元素不在 → 抛 `SkipCandidate`，外层自动 swipe 切下一个 |
| `skip_if_exists` | 同上 | 元素在 → 跳过当前候选（去重用，例如"关注"按钮还在说明没操作过） |
| 所有 action | `optional: true` | 失败不中断流程 |

**值占位符**：`value_from: keyword` / `value_from: dm_template`，自动取 yaml 默认或 dashboard 覆盖值；dm_template 里的 `{keyword}` 也会被替换。

---

## 5. 调试技巧

### 5.1 抓 UI 树（找新按钮）

```bash
# 推荐: dashboard 上点 inspect, 自动落到 outputs/inspect_<ts>/
#   ├── current.png
#   ├── current.xml
#   └── visible_text.txt  (text/desc/resource-id 去重列表)

# 或手动:
.venv/bin/python -c "
import uiautomator2 as u2
d = u2.connect()
print(d.dump_hierarchy())
" > /tmp/dump.xml
```

### 5.2 测某个坐标 / 文字是否能点

```bash
adb shell input tap 540 1200             # 比例坐标 → 像素坐标(1080×2340)
adb shell uiautomator dump /sdcard/u.xml && adb pull /sdcard/u.xml /tmp/
grep -A1 "发私信" /tmp/u.xml             # 找按钮属性
```

### 5.3 看当前在哪个 Activity

```bash
adb shell "dumpsys activity activities" | grep ResumedActivity | head -1
```

### 5.4 实时投屏看跑流程

```bash
scrcpy --max-fps=15 -w
```

### 5.5 失败现场自动 dump

`per_item` 任意一步出错，引擎自动落盘：

```
outputs/run_<ts>/
├── _fail_<i>_screen.png    # 失败那一刻的全屏截图
└── _fail_<i>_ui.xml        # 失败那一刻的完整 UI 树
```

然后 `back × 3` + 上滑切下一个，**继续后面的候选**。不会因单个失败 abort 整 batch。

---

## 6. 故障排除

| 现象 | 排查 |
|------|------|
| `adb devices` 是空 | USB 线只能充电 / 没授权 → 换线、换口、重新允许调试 |
| 控制台显示「未连接」 | 同上，再刷新浏览器 |
| `tap_text "搜索"` 失败 | 抖音冷启动慢 → `open_search` 头部 `wait` 加到 5-6s |
| `tap_text "发私信"` 失败 | 博主主页加载慢 → `obj.exists` timeout 默认 3s，可改 |
| `tap_text "视频"` 失败 | 还在搜索建议页，没真搜索 → 用 `tap_text "搜索"` 提交 |
| `input_text` 报 `null object reference` | 上一步没真进入输入框 → 检查 tap 是否命中 |
| 视频卡片 UI 树没文字 | 抖音 SurfaceView 渲染，只能用 `tap_xy` 比例坐标 |
| 点头像 `tap_xy` 落到点赞按钮上 | 视频带合集 / 商品挂件，右侧栏整体上移 → 改用 `tap_resource_id` value: `com.ss.android.ugc.aweme:id/user_avatar` |
| 莫名其妙跳到上次 Activity | `u2.app_start` 在抖音上会 restore → 必须 `adb am force-stop` + `monkey LAUNCHER`（已修） |
| u2 的 `click()` 没反应 | 抖音视频流吸收合成事件 → 必须 `adb shell input tap`（已修） |
| `back` 多了跳回搜索结果 | 数层级：私信→主页→视频 是 3 层，回视频只需 2 次 back |
| MAC 显示「受限」 | 正常，Android 10+ 不让用户拿真实 MAC，只有 root 能 |
| 浏览器看到旧工作流名 | 改了 yaml 后没刷新浏览器，⌘+R 即可 |
| 单候选失败后想看现场 | 看 `outputs/run_<ts>/_fail_*_screen.png` 和 `_fail_*_ui.xml` |

---

## 7. 手机端配置（其他品牌）

| 品牌 | 路径 |
|------|------|
| 华为 / 荣耀 | 设置 → 关于手机 → 连续点版本号 |
| 小米 / Redmi | 设置 → 我的设备 → 连续点 MIUI/HyperOS 版本 |
| OPPO / realme | 设置 → 关于本机 → 版本信息 → 连续点版本号 |
| vivo / iQOO | 设置 → 我的设备 → 连续点软件版本号 |
| 三星 | 设置 → 关于手机 → 软件信息 → 连续点版本号 |
| 一加 | 设置 → 关于设备 → 连续点版本号 |
| 原生 Android (Pixel) | 设置 → 关于手机 → 连续点版本号 |

进开发人员选项后**全打开**：USB 调试 / 仅充电模式下允许 ADB 调试 / USB 安装。

---

## 8. 安全 & 合规

- **当前默认全自动发送**，不二次确认 —— 引擎执行到 `tap "发送"` 即真发
- 私信节奏：**~22-24 秒 / 条**（冷启 + 搜索 + 关注 + 头像 resource-id 抓 UI + 发送 + back×2 + swipe；v9 因 resource-id 比 v8 多 ~200ms）
- 抖音对**陌生人私信**有硬限制：未回复前最多 3 条
- 抖音用户协议禁止「未经授权的自动化访问」，自动操作有**账号封禁**风险
- 单候选失败自动 dump `_fail_*` 取证，不影响后续候选
- `outputs/` 已加入 `.gitignore`，里面的真实截图 / 私信记录不上传
- 项目仅供学习和受授权场景使用
- 用户的实际经验：手动 200 条/天没事，自动节奏请根据账号状态调整

---

## 9. 端口和环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `DASHBOARD_PORT` | 8003 | 控制台端口 |

```bash
DASHBOARD_PORT=8888 .venv/bin/python dashboard.py
```

---

## 10. 项目结构

```
rpa/
├── README.md                       # 本文件
├── .gitignore                      # 排除 .venv/、outputs/、*.log、__pycache__/
├── requirements.txt                # uiautomator2 / PyYAML / opencv / numpy / Pillow / Flask
├── rpa_mvp.py                      # RPA 引擎
├── dashboard.py                    # Web 控制台
├── templates/
│   └── index.html
├── static/
│   ├── dashboard.css
│   └── dashboard.js
├── workflows/
│   ├── douyin-dm.yaml              # 默认工作流：抖音私信 v9
│   ├── _template.yaml              # 新工作流模板
│   └── README.md                   # 写新工作流的指南
├── assets/
│   └── follow_plus.png             # tap_image 模板示例
├── backups/                        # 历代快照
│   ├── v8-baseline/                # v8 基线
│   ├── v8-with-faildump/           # v8 + 失败自动 dump
│   └── v9-resourceid/              # v9 头像 resource-id 抗漂移
├── .venv/                          # (gitignore)
└── outputs/                        # (gitignore — 含真实业务截图 / 私信记录)
    ├── inspect_<ts>/
    │   ├── current.png
    │   ├── current.xml
    │   └── visible_text.txt
    └── run_<ts>/
        ├── 000_first_video_<ts>.png
        ├── 001_dm_sent_<ts>.png
        ├── _fail_<i>_screen.png    # 失败现场（可选）
        ├── _fail_<i>_ui.xml
        ├── sent_log.jsonl
        └── summary.md
```

---

## 11. 技术栈

- [ADB](https://developer.android.com/tools/adb) —— Android Debug Bridge
- [uiautomator2](https://github.com/openatx/uiautomator2) —— Python 控 Android
- [ADBKeyboard](https://github.com/senzhk/ADBKeyBoard) —— 中文输入（已装在手机上）
- [OpenCV](https://opencv.org/) + [NumPy](https://numpy.org/) + [Pillow](https://python-pillow.org/) —— `tap_image` 多尺度模板匹配
- [Flask](https://flask.palletsprojects.com/) + SSE —— 控制台后端 + 实时日志推流
- [scrcpy](https://github.com/Genymobile/scrcpy) —— 投屏（可选，调试用）
- 前端：原生 HTML/CSS/JS，无框架
