#!/usr/bin/env bash
# 一台新机配置保活白名单的"一键"脚本。
#
# 用法:
#   1. 用 USB 线把手机插电脑(只在初始化时用,跑工作流不需要)
#   2. 手机开发者选项 → USB 调试 打开
#   3. ./scripts/keepalive_setup.sh
#
# 做什么:
#   - 自动:加 dyrpa agent 到电池优化白名单 (dumpsys deviceidle,各 OEM 都吃这条)
#   - 自动:打开 dyrpa agent 的"后台无限制运行"(appops)
#   - 半自动:按 OEM 跳到"启动管理"页面,提示用户手动勾选自启动/关联启动/后台活动
#   - 不能自动的:最近任务里上滑锁定卡片(各 OEM 手势不同,只能口头指导)
#
# 跨 OEM 验证情况:
#   - Honor / Huawei MagicOS / EMUI: ✅ 实测路径
#   - 小米 MIUI: ✅ 实测路径
#   - OPPO ColorOS / OnePlus: ✅ 实测路径
#   - vivo OriginOS: ⚠️ 路径已知但未实测,失败请手动按下面 fallback
#   - Samsung OneUI: ✅ 实测路径
#   - 原生 / Pixel: 不需要 OEM 步骤,只跑前两步即可
set -euo pipefail

PKG="com.dyrpa.agent"

if ! command -v adb >/dev/null 2>&1; then
    echo "✗ 找不到 adb,请先安装 platform-tools 并加进 PATH"
    exit 1
fi

DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$DEVICES" ]; then
    echo "✗ 没检测到已授权 USB 调试 的设备,请检查 adb devices"
    exit 1
fi

for SERIAL in $DEVICES; do
    echo ""
    echo "================================================================"
    echo "处理设备: $SERIAL"
    echo "================================================================"

    BRAND=$(adb -s "$SERIAL" shell getprop ro.product.brand 2>/dev/null | tr -d '\r' | tr '[:upper:]' '[:lower:]')
    MANUFACTURER=$(adb -s "$SERIAL" shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r' | tr '[:upper:]' '[:lower:]')
    MODEL=$(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    echo "品牌: $BRAND / 厂商: $MANUFACTURER / 型号: $MODEL"

    # 检测 APK 是否装了
    INSTALLED=$(adb -s "$SERIAL" shell pm list packages "$PKG" | tr -d '\r')
    if [ -z "$INSTALLED" ]; then
        echo "✗ $PKG 未安装,跳过此设备。请先 adb install dyrpa-agent.apk"
        continue
    fi

    # ---------- 1. 电池优化白名单(所有 OEM 都吃 dumpsys) ----------
    echo "[1/3] 加进电池优化白名单..."
    adb -s "$SERIAL" shell dumpsys deviceidle whitelist "+$PKG" >/dev/null 2>&1 || true
    if adb -s "$SERIAL" shell dumpsys deviceidle whitelist 2>/dev/null | grep -q "$PKG"; then
        echo "    ✓ 已在白名单"
    else
        echo "    ⚠ 未生效,请手动 设置 → 电池 → 电池优化 → 找到 dyrpa agent → 不优化"
    fi

    # ---------- 2. 后台无限制 appops ----------
    echo "[2/3] 设置 RUN_ANY_IN_BACKGROUND..."
    adb -s "$SERIAL" shell cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
    echo "    ✓ 已允许 (如失败需 root,跳过)"

    # ---------- 3. OEM 启动管理 ----------
    echo "[3/3] 跳转 OEM 启动管理页面..."
    case "$BRAND" in
        honor|huawei)
            adb -s "$SERIAL" shell am start -n com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity 2>/dev/null \
                || adb -s "$SERIAL" shell am start -n com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity 2>/dev/null \
                || adb -s "$SERIAL" shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$PKG"
            echo "    → 在手机上: 找到 [dyrpa agent] → 改成 \"手动管理\" → 勾上 自启动 / 关联启动 / 后台活动 三项"
            echo "    → 然后回到最近任务,长按 dyrpa agent 卡片向上滑,看到锁标志即锁定"
            ;;
        xiaomi|redmi|poco)
            adb -s "$SERIAL" shell am start -n com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity 2>/dev/null \
                || adb -s "$SERIAL" shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$PKG"
            echo "    → 在手机上: 找到 [dyrpa agent] 开关打开"
            echo "    → 再去 设置 → 应用设置 → 应用管理 → dyrpa agent → 省电策略 → 无限制"
            ;;
        oppo|oneplus|realme)
            adb -s "$SERIAL" shell am start -n com.coloros.safecenter/.startupapp.StartupAppListActivity 2>/dev/null \
                || adb -s "$SERIAL" shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$PKG"
            echo "    → 在手机上: 找到 [dyrpa agent] → 允许自启动"
            echo "    → 设置 → 电池 → 应用耗电管理 → dyrpa agent → 允许后台运行"
            ;;
        vivo|iqoo)
            adb -s "$SERIAL" shell am start -n com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager 2>/dev/null \
                || adb -s "$SERIAL" shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$PKG"
            echo "    → 在手机上: 后台高耗电 → 找到 [dyrpa agent] → 允许"
            echo "    → 自启动管理 → 找到 [dyrpa agent] → 允许"
            ;;
        samsung)
            adb -s "$SERIAL" shell am start -n com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity 2>/dev/null \
                || adb -s "$SERIAL" shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$PKG"
            echo "    → 设置 → 应用 → dyrpa agent → 电池 → 不允许的休眠应用 / 允许后台活动"
            ;;
        google|pixel)
            echo "    原生 Android,无需 OEM 启动管理。已完成。"
            ;;
        *)
            adb -s "$SERIAL" shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$PKG"
            echo "    未识别 OEM ($BRAND),已跳转通用应用详情页"
            echo "    → 手动找:启动管理 / 自启动 / 后台运行 / 不优化电池"
            ;;
    esac
done

echo ""
echo "================================================================"
echo "全部完成"
echo "================================================================"
echo "下一步:"
echo "  1. 各设备按提示完成 OEM 启动管理"
echo "  2. 最近任务里上滑锁定 dyrpa agent"
echo "  3. 装 Stellar/Shizuku 并配对(只在第一次装,做完拔 USB)"
echo "  4. 打开 dyrpa agent → 配置 broker 和 device_id → 启动"
