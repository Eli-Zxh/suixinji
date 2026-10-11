## 改动概述

<!-- 2-3 句：解决什么问题、用什么方式。若是崩溃/内存修复，说明触发条件。 -->

关联 issue：Closes #

## 影响范围

- 涉及模块：<!-- ASR / LLM / 编辑器 / SRT / 长任务 / 模型导入 / WebDAV / UI / Native -->
- 是否改动 native（`app/src/main/cpp/**`、CMake、OpenCL 通道）：否 / 是
- 是否改动构建配置（`build.gradle.kts`、`settings.gradle.kts`、`libs.versions.toml`、wrapper）：否 / 是
- 是否改动任务生命周期（Service、心跳 `running.json`、断点续跑、checkpoints）：否 / 是
- 预计规模：<!-- ≤400 行为宜；更大请在下方拆分说明 -->

## 本地构建检查

- [ ] `./gradlew spotlessCheck` 通过（改过 md/xml/toml 也要跑，README 尾部空行会被判失败）
- [ ] `./gradlew testDebugUnitTest` 通过
- [ ] `./gradlew assembleDebug` 通过，产物 `app/build/outputs/apk/debug/suixinji-v*-debug.apk`
- [ ] 未新增 >5MB 的文件；**没有**提交 `models/`、`sdk/`、`app/build/`、`local.properties`、`*.keystore`

## 真机验证（必须，CI 跑不了端侧推理）

- [ ] 已安装 debug 包到 arm64 真机并实际走一遍
- [ ] 主链路：录音 → 转写 → AI 归纳 → 笔记落盘 → 首页可见
- [ ] 若涉及长任务：中途切后台/被杀后能恢复或明确提示
- [ ] `files/logs/` 下 `crash.log`、`native_crash.log` 无本次新增记录
- [ ] 内存峰值与耗时未明显劣化（贴 `MEM[...]` 采样或阶段耗时）
- [ ] 机型与 ROM：

## 隐私与安全

- [ ] 日志/截图里不含笔记正文、绝对路径、WebDAV 账号
- [ ] 未引入新的联网上报或远端服务（本项目应全离线；WebDAV 仅用户主动配置）
- [ ] 新增依赖来源可信，许可证可接受

## 补充说明

<!-- 截图/录屏、回滚方式、未尽事项。原生部分改动请写明为什么这样绕（如 libOpenCL.so 空壳不得进 APK）。 -->
