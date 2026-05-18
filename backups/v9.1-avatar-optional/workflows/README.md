# workflows/ — 工作流定义

每个 `*.yaml` 文件是一个独立工作流。Dashboard 启动时扫描这个目录，把所有 yaml 列在下拉里供选择。

下划线开头的文件（如 `_template.yaml`）会被忽略，只作为模板。

## 写一个新工作流

```bash
cd workflows
cp _template.yaml my-new-workflow.yaml
```

然后改这几处：

1. **`meta.name`** — Dashboard 下拉里看到的名字
2. **`meta.description`** — 一句话说明
3. **`workflow.keyword`** — 默认搜索词
4. **`workflow.open_search` / `submit_search` / `per_item`** — 三段步骤序列
5. **`drafts.dm_template`** — 话术（如果要发私信）

存盘后**刷新 dashboard 页面**就能看到。

## 步骤序列的三段含义

| 段 | 何时执行 | 用法 |
|----|---------|------|
| `open_search` | 工作流开始时执行**一次** | 打开 app → 进搜索框 |
| `submit_search` | `open_search` 后执行**一次** | 输入关键词 + 提交 + 切 tab + 进第一个候选 |
| `per_item` | 重复 `max_items` 次 | 对每个候选人的具体动作(关注/私信/评论/...) |

末尾一般要在 `per_item` 加 `swipe` 滑动到下一个候选，否则会一直停在同一个。

## 调试新工作流

1. 不动 dashboard，刷新页面让它识别新 yaml
2. 在下拉里选你的新工作流
3. 把 `max_items` 设成 1，跑一次看效果
4. 看 dashboard 右边的实时日志，每个 action 都会打印
5. 看 `outputs/run_*/` 里的截图判断每步对不对
6. 调坐标/wait/选择器，直到稳定，再放大 `max_items`

## 常踩的坑

- **`press enter` 不会触发搜索** — 要点屏幕上「搜索」按钮（`tap_text "搜索"`）
- **`input_text` 失败** — 当前 UI 没有 EditText 获焦。检查上一步是否真的进了输入界面
- **`tap_text "视频"` 失败** — 说明还在搜索建议页，没真正搜索。先 tap 搜索按钮
- **抖音冷启动慢** — `open_search` 开头加 `wait 4s`
- **视频卡片在 UI 树里没文字** — 抖音 SurfaceView 渲染，只能用 `tap_xy` 比例坐标
- **`back` 按多了** — 数清楚层级，比如 视频→主页→私信 是 3 层，回视频只要按 2 次
- **重复给同一人发** — `per_item` 开头加 `skip_if_not_exists desc: "关注"` 用关注按钮存在性去重

## 引用

完整 action 列表见 `_template.yaml` 底部。
