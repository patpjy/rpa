# v9-resourceid（最新完整版）

中间态基础上把点头像改成按 Android resource-id 查找。

**比 v8-with-faildump 多**:
- 引擎加 `tap_resource_id` 动作
- yaml 头像那一步从 `tap_xy (987,1100)` 改为 `tap_resource_id: com.ss.android.ugc.aweme:id/user_avatar`
- 视频带挂件（合集 / 商品 / 直播预告）右侧栏漂移时自动适配头像真实位置

**代价**: 点头像那一步多 ~200ms（10 条候选总共多 2 秒）。

**恢复方法**: 在 rpa 目录执行
```bash
cp -r backups/v9-resourceid/* .
```
