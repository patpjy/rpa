# v8-with-faildump（中间态）

v8 基础上加了：失败诊断 + dashboard 输入私信话术。

**比 v8-baseline 多**:
- per_item 出错时自动 dump 截图+UI 到 `outputs/run_*/_fail_<n>_*`
- dashboard 控制卡片多一个"私信话术"textarea，可改话术不动 yaml

**还差**: 视频带挂件（如"合集"）时点头像会落到点赞按钮上（绝对像素问题，v9 修复）。

**恢复方法**: 在 rpa 目录执行
```bash
cp -r backups/v8-with-faildump/* .
```
