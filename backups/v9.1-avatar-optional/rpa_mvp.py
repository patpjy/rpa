#!/usr/bin/env python3
"""Single-device Android RPA learning MVP.

The runner automates exploration and data collection, but deliberately stops at
comment / DM drafts. Sending is left to the human operator.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

try:
    import yaml
except ImportError:  # pragma: no cover
    yaml = None


ROOT = Path(__file__).resolve().parent
OUTPUT_ROOT = ROOT / "outputs"


LOG_BUFFER: deque = deque(maxlen=500)
STATE: Dict[str, Any] = {
    "running": False,
    "task_kind": None,
    "current_index": 0,
    "total": 0,
    "started_at": None,
    "ended_at": None,
    "last_screenshot": None,
    "stop_requested": False,
    "override_keyword": None,
    "override_max_items": None,
    "override_dm_template": None,
}


class _BufferHandler(logging.Handler):
    def emit(self, record: logging.LogRecord) -> None:
        try:
            LOG_BUFFER.append((time.time(), self.format(record)))
        except Exception:
            self.handleError(record)


logger = logging.getLogger("rpa")
if not logger.handlers:
    logger.setLevel(logging.INFO)
    _fmt = logging.Formatter("[%(asctime)s] [rpa] %(message)s", datefmt="%H:%M:%S")
    _stream = logging.StreamHandler()
    _stream.setFormatter(_fmt)
    _buffer = _BufferHandler()
    _buffer.setFormatter(_fmt)
    logger.addHandler(_stream)
    logger.addHandler(_buffer)
    logger.propagate = False


class ConfigError(RuntimeError):
    pass


class SkipCandidate(RuntimeError):
    """从 per_item 内部抛出,表示放弃当前候选人(已关注/无关视频/广告等)。
    run_workflow 会 catch 后自动上滑切到下一个视频。"""
    pass


class AppLaunchError(RuntimeError):
    """app_start 失败: 包未装 / monkey 异常 / 目标未到前台。"""
    pass


@dataclass
class Candidate:
    """Lightweight record of one per_item run. Mostly used to bundle the
    screenshots / xml dumps so write_outputs() can serialize them."""
    index: int
    visible_text: List[str] = field(default_factory=list)
    screenshots: List[str] = field(default_factory=list)
    xml_files: List[str] = field(default_factory=list)

    def to_json(self) -> Dict[str, Any]:
        return {
            "index": self.index,
            "visible_text": self.visible_text,
            "screenshots": self.screenshots,
            "xml_files": self.xml_files,
        }


def load_config(path: Path) -> Dict[str, Any]:
    if yaml is None:
        raise ConfigError("Missing PyYAML. Install dependencies with: python3 -m pip install -r requirements.txt")
    if not path.exists():
        raise ConfigError(f"Config not found: {path}")
    with path.open("r", encoding="utf-8") as f:
        data = yaml.safe_load(f) or {}
    if not isinstance(data, dict):
        raise ConfigError("Config must be a YAML mapping")
    return data


def command_exists(name: str) -> bool:
    return shutil.which(name) is not None


def run_doctor() -> int:
    print(f"Python: {sys.version.split()[0]}")
    print(f"adb: {'OK' if command_exists('adb') else 'not found'}")
    print(f"scrcpy: {'OK' if command_exists('scrcpy') else 'not found'}")
    try:
        import uiautomator2  # noqa: F401
        print("uiautomator2: OK")
    except ImportError:
        print("uiautomator2: not installed")
    if command_exists("adb"):
        result = subprocess.run(["adb", "devices"], text=True, capture_output=True, check=False)
        print(result.stdout.strip() or result.stderr.strip())
    return 0


class Device:
    def __init__(self, serial: Optional[str], dry_run: bool) -> None:
        self.serial = serial
        self.dry_run = dry_run
        self._driver = None

    @property
    def d(self):
        if self._driver is None:
            try:
                import uiautomator2 as u2
            except ImportError as exc:
                raise RuntimeError("Install dependencies first: python3 -m pip install -r requirements.txt") from exc
            self._driver = u2.connect(self.serial) if self.serial else u2.connect()
        return self._driver

    def window_size(self) -> tuple[int, int]:
        if self.dry_run:
            return (1080, 1920)
        return self.d.window_size()

    def app_start(self, package: str) -> None:
        self.log(f"start app {package} (force cold start)")
        if self.dry_run:
            return
        # u2.app_stop/app_start 在抖音上会被 restore 到上次的 Activity (比如 ChatRoomActivity)
        # 必须用 adb am force-stop + monkey LAUNCHER intent 才能真正冷启动到首页
        if not self._is_package_installed(package):
            suggestions = self._suggest_similar_packages(package)
            hint = f"; 相近包: {suggestions}" if suggestions else ""
            raise AppLaunchError(
                f"package {package} 未在设备 {self.serial or 'default'} 上安装{hint}"
            )
        self._adb_text("shell", "am", "force-stop", package, timeout=4)
        time.sleep(1.0)
        # HUAWEI EMUI 上 monkey 即使打印 "No activities found" 仍返回 exit=0,
        # 不能只靠 rc 判断,必须扫文本
        rc, out, err = self._adb_text(
            "shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1",
            timeout=6,
        )
        combined = (out + err).lower()
        if "no activities found" in combined or "monkey aborted" in combined:
            msg = (err or out).strip()[:200]
            raise AppLaunchError(f"monkey 启动 {package} 失败 (rc={rc}): {msg}")
        deadline = time.monotonic() + 5.0
        last_pkg, last_act = "", ""
        while time.monotonic() < deadline:
            last_pkg, last_act = self._current_foreground()
            if last_pkg == package:
                self.log(f"app foreground confirmed: {package}/{last_act}")
                return
            time.sleep(0.5)
        raise AppLaunchError(
            f"启动 {package} 后 5s 未到前台, 当前前台: {last_pkg or '?'}/{last_act or '?'}"
        )

    def _adb_text(self, *args: str, timeout: float = 4) -> tuple[int, str, str]:
        cmd = ["adb"]
        if self.serial:
            cmd += ["-s", self.serial]
        cmd += list(args)
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=False)
        return r.returncode, (r.stdout or ""), (r.stderr or "")

    def _is_package_installed(self, package: str) -> bool:
        # pm list packages 默认前缀匹配 (com.foo 会同时命中 com.foo / com.foo.lite),
        # 必须用整行精确等于判断,不能用 in
        _, out, _ = self._adb_text("shell", "pm", "list", "packages", package)
        target = f"package:{package}"
        return any(line.strip() == target for line in out.splitlines())

    def _suggest_similar_packages(self, package: str) -> list[str]:
        # 先用最右一段 (例: aweme),无命中回退到去尾的前缀 (例: com.ss.android.ugc.aweme)。
        # pm list packages 走子串匹配,所以前缀本身就能匹到 .lite 等变体
        candidates = [package.rsplit(".", 1)[-1]] if "." in package else [package]
        if "." in package:
            candidates.append(package.rsplit(".", 1)[0])
        seen: set = set()
        for keyword in candidates:
            if not keyword or keyword in seen:
                continue
            seen.add(keyword)
            _, out, _ = self._adb_text("shell", "pm", "list", "packages", keyword)
            results = [
                line.replace("package:", "").strip()
                for line in out.splitlines()
                if line.startswith("package:")
            ]
            if results:
                return results
        return []

    def _current_foreground(self) -> tuple[str, str]:
        _, out, _ = self._adb_text("shell", "dumpsys", "window", timeout=4)
        for line in out.splitlines():
            line = line.strip()
            if "mCurrentFocus" not in line:
                continue
            m = re.search(r"\s([A-Za-z][\w.]*)/([A-Za-z][\w.$]*)", line)
            if m:
                return m.group(1), m.group(2)
        return "", ""

    def _adb_tap(self, px: int, py: int) -> None:
        """Send touch via `adb shell input tap`. uiautomator2's d.click() turns
        out to be silently ignored by Douyin's video screen (likely a custom
        view layer absorbs the synthesized event). adb input tap injects at the
        kernel level and actually triggers the button."""
        cmd = ["adb"]
        if self.serial:
            cmd += ["-s", self.serial]
        cmd += ["shell", "input", "tap", str(px), str(py)]
        subprocess.run(cmd, check=False, timeout=3)

    def _tap_element_center(self, obj) -> None:
        try:
            b = obj.info["bounds"]
            cx = (int(b["left"]) + int(b["right"])) // 2
            cy = (int(b["top"]) + int(b["bottom"])) // 2
            self._adb_tap(cx, cy)
        except Exception:
            obj.click()

    def tap_text(self, text: str, clickable: bool = False) -> bool:
        self.log(f"tap text={text!r}" + (" clickable" if clickable else ""))
        if self.dry_run:
            return True
        kwargs = {"clickable": True} if clickable else {}
        obj = self.d(text=text, **kwargs)
        # timeout 3s 给页面加载留出时间 — 1s 在主页加载稍慢时会误报失败
        if obj.exists(timeout=3.0):
            self._tap_element_center(obj)
            return True
        return False

    def tap_desc(self, text: str, clickable: bool = False) -> bool:
        self.log(f"tap desc={text!r}" + (" clickable" if clickable else ""))
        if self.dry_run:
            return True
        kwargs = {"clickable": True} if clickable else {}
        obj = self.d(description=text, **kwargs)
        if obj.exists(timeout=3.0):
            self._tap_element_center(obj)
            return True
        return False

    def tap_xy(self, x: float, y: float) -> bool:
        width, height = self.window_size()
        px, py = int(width * x), int(height * y)
        self.log(f"tap xy={x:.2f},{y:.2f} -> {px},{py}")
        if not self.dry_run:
            self._adb_tap(px, py)
        return True

    def tap_resource_id(self, resource_id: str, clickable: bool = False) -> bool:
        """按 Android resource-id 查找元素并 tap 中心。
        比 tap_xy 慢 ~200ms (要先抓 UI 树),但抗布局漂移 —
        视频带挂件/浮层导致右侧栏整体上下浮动时,坐标会落空,resource-id 不会。
        """
        self.log(f"tap resource-id={resource_id!r}" + (" clickable" if clickable else ""))
        if self.dry_run:
            return True
        kwargs = {"clickable": True} if clickable else {}
        obj = self.d(resourceId=resource_id, **kwargs)
        if obj.exists(timeout=3.0):
            self._tap_element_center(obj)
            return True
        return False

    def input_text(self, text: str) -> bool:
        self.log(f"input text={text!r}")
        if not self.dry_run:
            self.d.send_keys(text, clear=True)
        return True

    def press(self, key: str) -> bool:
        self.log(f"press {key}")
        if not self.dry_run:
            self.d.press(key)
        return True

    def swipe(self, start: Iterable[float], end: Iterable[float], duration: float) -> bool:
        sx, sy = list(start)
        ex, ey = list(end)
        width, height = self.window_size()
        self.log(f"swipe {sx:.2f},{sy:.2f} -> {ex:.2f},{ey:.2f}")
        if not self.dry_run:
            self.d.swipe(int(width * sx), int(height * sy), int(width * ex), int(height * ey), duration=duration)
        return True

    def screenshot(self, path: Path) -> bool:
        self.log(f"screenshot {path.name}")
        if not self.dry_run:
            self.d.screenshot(str(path))
        else:
            path.write_text("dry-run screenshot placeholder\n", encoding="utf-8")
        STATE["last_screenshot"] = str(path)
        return True

    def dump_hierarchy(self, path: Path) -> str:
        self.log(f"dump hierarchy {path.name}")
        if self.dry_run:
            xml = "<hierarchy><node text='dry run 装修 设计 房产' content-desc=''/></hierarchy>"
        else:
            xml = self.d.dump_hierarchy()
        path.write_text(xml, encoding="utf-8")
        return xml

    def image_exists(self, template_path: str, threshold: float = 0.75) -> bool:
        """Same multi-scale template matching as tap_image, but only checks
        whether the template is present on screen — doesn't click."""
        abs_template = template_path if os.path.isabs(template_path) else str(ROOT / template_path)
        if not os.path.exists(abs_template):
            self.log(f"image_exists: template not found {abs_template}")
            return False
        if self.dry_run:
            return True
        try:
            import cv2
            import numpy as np
        except ImportError as exc:
            raise RuntimeError("Image deps missing") from exc
        pil_img = self.d.screenshot()
        screen = cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)
        template = cv2.imread(abs_template)
        if template is None:
            self.log(f"image_exists: cannot decode {abs_template}")
            return False
        best_conf = -1.0
        h0, w0 = template.shape[:2]
        for scale in (0.7, 0.85, 1.0, 1.15, 1.3):
            nw, nh = int(w0 * scale), int(h0 * scale)
            if nw < 8 or nh < 8 or nw > screen.shape[1] or nh > screen.shape[0]:
                continue
            resized = cv2.resize(template, (nw, nh))
            result = cv2.matchTemplate(screen, resized, cv2.TM_CCOEFF_NORMED)
            _, max_val, _, _ = cv2.minMaxLoc(result)
            if max_val > best_conf:
                best_conf = float(max_val)
        found = best_conf >= threshold
        verdict = "YES" if found else "NO"
        self.log(f"image_exists: {os.path.basename(abs_template)} conf={best_conf:.3f} → {verdict} (threshold={threshold})")
        return found

    def tap_image(
        self,
        template_path: str,
        threshold: float = 0.8,
        offset_jitter: int = 8,
    ) -> bool:
        """Image template match + click. Multi-scale to tolerate resolution drift,
        plus random pixel jitter so taps don't all land on the geometric center."""
        abs_template = template_path if os.path.isabs(template_path) else str(ROOT / template_path)
        if not os.path.exists(abs_template):
            self.log(f"tap_image: template not found {abs_template}")
            return False
        self.log(f"tap_image template={os.path.basename(abs_template)} threshold={threshold}")
        if self.dry_run:
            return True
        try:
            import cv2
            import numpy as np
        except ImportError as exc:
            raise RuntimeError(
                "Image deps missing. Run: python3 -m pip install -r requirements.txt"
            ) from exc

        pil_img = self.d.screenshot()
        screen = cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)
        template = cv2.imread(abs_template)
        if template is None:
            self.log(f"tap_image: cannot decode {abs_template}")
            return False

        best_conf = -1.0
        best_center: Optional[tuple[int, int]] = None
        best_scale = 1.0
        h0, w0 = template.shape[:2]
        for scale in (0.7, 0.85, 1.0, 1.15, 1.3):
            nw, nh = int(w0 * scale), int(h0 * scale)
            if nw < 8 or nh < 8 or nw > screen.shape[1] or nh > screen.shape[0]:
                continue
            resized = cv2.resize(template, (nw, nh))
            result = cv2.matchTemplate(screen, resized, cv2.TM_CCOEFF_NORMED)
            _, max_val, _, max_loc = cv2.minMaxLoc(result)
            if max_val > best_conf:
                best_conf = float(max_val)
                best_center = (max_loc[0] + nw // 2, max_loc[1] + nh // 2)
                best_scale = scale

        if best_center is None or best_conf < threshold:
            self.log(f"tap_image: no match (best confidence={best_conf:.3f} < {threshold})")
            return False

        cx, cy = best_center
        if offset_jitter > 0:
            import random
            cx += random.randint(-offset_jitter, offset_jitter)
            cy += random.randint(-offset_jitter, offset_jitter)
        self.log(f"tap_image -> ({cx},{cy}) confidence={best_conf:.3f} scale={best_scale:.2f}")
        self.d.click(cx, cy)
        return True

    @staticmethod
    def log(message: str) -> None:
        logger.info(message)


def extract_visible_text(xml_text: str) -> List[str]:
    values: List[str] = []
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return values
    for node in root.iter():
        for attr in ("text", "content-desc", "resource-id"):
            value = (node.attrib.get(attr) or "").strip()
            if value and value not in values:
                values.append(value)
    return values


def resolve_value(step: Dict[str, Any], config: Dict[str, Any]) -> str:
    if "value_from" in step:
        key = step["value_from"]
        if key == "keyword":
            return str(config.get("workflow", {}).get("keyword", ""))
        if key == "dm_template":
            # dashboard 输入框传入的 override_dm_template 优先于 yaml 里的 drafts.dm_template
            override = STATE.get("override_dm_template")
            template = str(override) if override else str(config.get("drafts", {}).get("dm_template", ""))
            keyword = str(config.get("workflow", {}).get("keyword", ""))
            return template.replace("{keyword}", keyword)
        raise ConfigError(f"Unsupported value_from: {key}")
    return str(step.get("value", ""))


def execute_steps(
    device: Device,
    steps: List[Dict[str, Any]],
    config: Dict[str, Any],
    out_dir: Path,
    candidate: Optional[Candidate] = None,
) -> None:
    default_wait = float(config.get("workflow", {}).get("wait_after_action", 0.7))
    for step in steps:
        # 每步开头检查 stop —— 让点"停止"按钮在 <1s 内生效
        if STATE.get("stop_requested"):
            device.log("stop requested mid-step, abort")
            return
        action = step.get("action")
        optional = bool(step.get("optional", False))
        ok = True
        if action == "tap_text":
            ok = device.tap_text(resolve_value(step, config), clickable=bool(step.get("clickable", False)))
        elif action == "tap_desc":
            ok = device.tap_desc(resolve_value(step, config), clickable=bool(step.get("clickable", False)))
        elif action == "tap_resource_id":
            ok = device.tap_resource_id(resolve_value(step, config), clickable=bool(step.get("clickable", False)))
        elif action == "tap_xy":
            ok = device.tap_xy(float(step["x"]), float(step["y"]))
        elif action == "input_text":
            ok = device.input_text(resolve_value(step, config))
        elif action == "press":
            ok = device.press(str(step["key"]))
        elif action == "swipe":
            ok = device.swipe(step["start"], step["end"], float(step.get("duration", 0.4)))
        elif action == "skip_if_not_exists":
            target_text = step.get("text")
            target_desc = step.get("desc")
            target_template = step.get("template")
            template_threshold = float(step.get("threshold", 0.75))
            clickable = bool(step.get("clickable", False))
            reason = step.get("reason", "required element missing")
            if device.dry_run:
                device.log(f"skip_if_not_exists (dry-run): assume present")
            else:
                kwargs = {"clickable": True} if clickable else {}
                found = False
                if target_template and device.image_exists(target_template, threshold=template_threshold):
                    found = True
                if target_text and not found and device.d(text=target_text, **kwargs).exists(timeout=1.5):
                    found = True
                if target_desc and not found and device.d(description=target_desc, **kwargs).exists(timeout=1.5):
                    found = True
                if not found:
                    device.log(f"SKIP candidate: {reason}")
                    raise SkipCandidate(reason)
                device.log(f"check ok: {target_template or target_text or target_desc} present")
        elif action == "skip_if_exists":
            target_text = step.get("text")
            target_desc = step.get("desc")
            target_template = step.get("template")
            template_threshold = float(step.get("threshold", 0.75))
            clickable = bool(step.get("clickable", False))
            reason = step.get("reason", "forbidden element present")
            if device.dry_run:
                device.log(f"skip_if_exists (dry-run): assume absent")
            else:
                kwargs = {"clickable": True} if clickable else {}
                found = False
                if target_template and device.image_exists(target_template, threshold=template_threshold):
                    found = True
                if target_text and not found and device.d(text=target_text, **kwargs).exists(timeout=1.0):
                    found = True
                if target_desc and not found and device.d(description=target_desc, **kwargs).exists(timeout=1.0):
                    found = True
                if found:
                    device.log(f"SKIP candidate: {reason}")
                    raise SkipCandidate(reason)
        elif action == "tap_xy_if_missing":
            # 如果指定的 text/desc 元素 NOT 存在, 才 tap 给定坐标。
            # 用于条件 fallback: 例如 "如果当前没有'发私信'按钮(说明还在视频页),
            # 就 tap 视频左下角的博主名进主页"
            target_text = step.get("text")
            target_desc = step.get("desc")
            clickable = bool(step.get("clickable", False))
            kwargs = {"clickable": True} if clickable else {}
            found = False
            if device.dry_run:
                found = True
            else:
                if target_text and device.d(text=target_text, **kwargs).exists(timeout=1.0):
                    found = True
                if target_desc and not found and device.d(description=target_desc, **kwargs).exists(timeout=1.0):
                    found = True
            if found:
                device.log(f"{target_text or target_desc} present, skip fallback tap_xy")
            else:
                ok = device.tap_xy(float(step["x"]), float(step["y"]))
        elif action == "tap_relative_to_element":
            # 找一个稳定的 UI 锚点元素,在它周围 (dx, dy) 像素偏移处 tap。
            # 抖音视频页几乎全是 SurfaceView,UI 树只有「评论」TextView 这一个稳定锚点,
            # 头像永远在它上方 ~470 px,无论视频布局如何变化。
            anchor_text = step.get("anchor_text")
            anchor_desc = step.get("anchor_desc")
            dx = int(step.get("dx", 0))
            dy = int(step.get("dy", 0))
            if device.dry_run:
                device.log(f"tap_relative_to_element (dry-run) anchor={anchor_text or anchor_desc} dx={dx} dy={dy}")
            else:
                if anchor_text:
                    anchor = device.d(text=anchor_text)
                elif anchor_desc:
                    anchor = device.d(description=anchor_desc)
                else:
                    raise ConfigError("tap_relative_to_element 需要 anchor_text 或 anchor_desc")
                if not anchor.exists(timeout=2.0):
                    device.log(f"tap_relative_to_element: 锚点 {anchor_text or anchor_desc!r} 找不到")
                    ok = False
                else:
                    b = anchor.info["bounds"]
                    ax = (int(b["left"]) + int(b["right"])) // 2
                    ay = (int(b["top"]) + int(b["bottom"])) // 2
                    tx, ty = ax + dx, ay + dy
                    device.log(f"tap_relative_to_element anchor={anchor_text or anchor_desc!r} ({ax},{ay}) + ({dx},{dy}) = ({tx},{ty})")
                    device._adb_tap(tx, ty)
        elif action == "tap_image":
            template = step.get("template") or step.get("value")
            if not template:
                raise ConfigError("tap_image step requires 'template' field")
            threshold = float(step.get("threshold", 0.8))
            jitter = int(step.get("offset_jitter", 8))
            ok = device.tap_image(str(template), threshold=threshold, offset_jitter=jitter)
        elif action == "wait":
            seconds = float(step.get("seconds", default_wait))
            device.log(f"wait {seconds}s")
            # 短 sleep 循环 + stop check —— 长 wait 也能立即响应停止按钮
            elapsed = 0.0
            while elapsed < seconds:
                if STATE.get("stop_requested"):
                    device.log("stop requested during wait, abort")
                    return
                slice_ = min(0.3, seconds - elapsed)
                time.sleep(slice_)
                elapsed += slice_
            continue
        elif action == "screenshot":
            name = step.get("name", "screen")
            index = candidate.index if candidate else 0
            path = out_dir / f"{index:03d}_{name}_{int(time.time())}.png"
            ok = device.screenshot(path)
            if candidate:
                candidate.screenshots.append(str(path))
        elif action == "collect_visible_text":
            name = step.get("name", "ui")
            index = candidate.index if candidate else 0
            path = out_dir / f"{index:03d}_{name}_{int(time.time())}.xml"
            xml_text = device.dump_hierarchy(path)
            values = extract_visible_text(xml_text)
            if candidate:
                candidate.xml_files.append(str(path))
                for value in values:
                    if value not in candidate.visible_text:
                        candidate.visible_text.append(value)
        else:
            raise ConfigError(f"Unsupported action: {action}")
        if not ok and not optional:
            raise RuntimeError(f"Required action failed: {action} {step}")
        if default_wait > 0:
            time.sleep(default_wait)


def write_outputs(candidates: List[Candidate], out_dir: Path, config: Dict[str, Any]) -> None:
    if not candidates:
        return
    log_path = out_dir / "sent_log.jsonl"
    with log_path.open("w", encoding="utf-8") as f:
        for candidate in candidates:
            f.write(json.dumps(candidate.to_json(), ensure_ascii=False) + "\n")
    summary = [
        f"# Run Summary",
        "",
        f"workflow: {config.get('meta', {}).get('name', '(unnamed)')}",
        f"keyword:  {config.get('workflow', {}).get('keyword', '')}",
        f"sent:     {len(candidates)} candidate(s)",
        "",
    ]
    for c in candidates:
        summary.append(f"- Candidate {c.index}: {len(c.screenshots)} screenshot(s)")
    (out_dir / "summary.md").write_text("\n".join(summary), encoding="utf-8")
    logger.info(f"wrote {log_path}")
    logger.info(f"wrote {out_dir / 'summary.md'}")


def run_workflow(config_path: Path, dry_run: Optional[bool]) -> int:
    config = load_config(config_path)
    if dry_run is None:
        # Safety default: dry-run unless the caller (CLI --live or dashboard) explicitly said live
        dry_run = True

    if STATE.get("override_keyword"):
        config.setdefault("workflow", {})["keyword"] = STATE["override_keyword"]
    if STATE.get("override_max_items") is not None:
        config.setdefault("workflow", {})["max_items"] = int(STATE["override_max_items"])

    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_dir = OUTPUT_ROOT / f"run_{ts}"
    out_dir.mkdir(parents=True, exist_ok=True)

    max_items = int(config.get("workflow", {}).get("max_items", 1))
    STATE.update({
        "running": True,
        "task_kind": "workflow",
        "current_index": 0,
        "total": max_items,
        "started_at": time.time(),
        "ended_at": None,
        "stop_requested": False,
    })
    try:
        device = Device(config.get("device", {}).get("serial"), dry_run=dry_run)
        package = str(config.get("app", {}).get("package", "")).strip()
        if not package:
            raise ConfigError("app.package is required")

        logger.info(f"output: {out_dir}")
        logger.info(f"mode: {'DRY RUN' if dry_run else 'LIVE'}")
        device.app_start(package)
        execute_steps(device, config.get("workflow", {}).get("open_search", []), config, out_dir)
        execute_steps(device, config.get("workflow", {}).get("submit_search", []), config, out_dir)

        candidates: List[Candidate] = []
        for i in range(1, max_items + 1):
            if STATE.get("stop_requested"):
                logger.warning("stop requested, breaking out of candidate loop")
                break
            STATE["current_index"] = i
            candidate = Candidate(index=i)
            try:
                execute_steps(device, config.get("workflow", {}).get("per_item", []), config, out_dir, candidate)
            except SkipCandidate as exc:
                logger.info(f"candidate {i} skipped ({exc}); swiping to next video")
                try:
                    device.swipe([0.5, 0.78], [0.5, 0.22], 0.4)
                    time.sleep(1.0)
                except Exception as swipe_err:
                    logger.warning(f"recovery swipe failed: {swipe_err}")
                continue
            except Exception as exc:
                # 单个候选失败(比如发私信按钮没出现)不应该 abort 整个 batch。
                # 按 back × 3 试图回视频页, swipe up 切下一个, 继续 loop
                logger.warning(f"candidate {i} failed ({exc}); recover + swipe + continue")
                # 失败现场存证 —— 立刻截图+抓 UI 树, 出错才落盘, 不影响成功路径
                try:
                    device.screenshot(out_dir / f"_fail_{i:03d}_screen.png")
                    device.dump_hierarchy(out_dir / f"_fail_{i:03d}_ui.xml")
                except Exception as dump_err:
                    logger.warning(f"failure dump failed: {dump_err}")
                for _ in range(3):
                    try:
                        device.press("back")
                        time.sleep(0.3)
                    except Exception:
                        pass
                try:
                    device.swipe([0.5, 0.78], [0.5, 0.22], 0.4)
                    time.sleep(1.0)
                except Exception as swipe_err:
                    logger.warning(f"recovery swipe failed: {swipe_err}")
                continue
            candidates.append(candidate)

        write_outputs(candidates, out_dir, config)
        return 0
    finally:
        STATE.update({
            "running": False,
            "ended_at": time.time(),
            "override_keyword": None,
            "override_max_items": None,
            "override_dm_template": None,
        })


def inspect_device(config_path: Path, dry_run: bool) -> int:
    """Dump current screen + UI tree. Called from dashboard.py only;
    no CLI entry — the CLI inspect subcommand has been removed."""
    config = load_config(config_path)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_dir = OUTPUT_ROOT / f"inspect_{ts}"
    out_dir.mkdir(parents=True, exist_ok=True)
    STATE.update({
        "running": True,
        "task_kind": "inspect",
        "current_index": 0,
        "total": 1,
        "started_at": time.time(),
        "ended_at": None,
        "stop_requested": False,
    })
    try:
        device = Device(config.get("device", {}).get("serial"), dry_run=dry_run)
        package = str(config.get("app", {}).get("package", "")).strip()
        if package:
            device.app_start(package)
            time.sleep(2)
        xml_path = out_dir / "current.xml"
        screenshot_path = out_dir / "current.png"
        xml_text = device.dump_hierarchy(xml_path)
        device.screenshot(screenshot_path)
        values = extract_visible_text(xml_text)
        (out_dir / "visible_text.txt").write_text("\n".join(values), encoding="utf-8")
        logger.info(f"wrote {xml_path}")
        logger.info(f"wrote {screenshot_path}")
        logger.info(f"wrote {out_dir / 'visible_text.txt'}")
        STATE["current_index"] = 1
        return 0
    finally:
        STATE.update({"running": False, "ended_at": time.time()})


def parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Android RPA engine")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("doctor", help="Check local tools and connected devices")

    run = sub.add_parser("run", help="Run a workflow yaml")
    run.add_argument("--config", default=str(ROOT / "workflows" / "gz-second-hand-dm.yaml"))
    run.add_argument("--live", action="store_true", help="Actually control the device")
    run.add_argument("--dry-run", action="store_true", help="Do not control a real device")
    return parser.parse_args(argv)


def main(argv: List[str]) -> int:
    args = parse_args(argv)
    try:
        if args.command == "doctor":
            return run_doctor()
        if args.command == "run":
            dry_run = False if args.live else True if args.dry_run else None
            return run_workflow(Path(args.config), dry_run)
    except (ConfigError, RuntimeError, KeyError, ValueError) as exc:
        logger.error(f"error: {exc}")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
