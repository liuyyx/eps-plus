# Addon Development Guide（已移除）

Epsilon 的 Addon 系统已在提交 `675fe80`（“删除 Addon 系统”）中整体移除：

- `common/src/main/java/com/github/epsilon/addon/` 包不存在
- `AddonManager`、`EpsilonAddon`、`EpsilonAddonSetupEvent` 不存在
- `fabric.mod.json` / NeoForge 均未声明任何 Addon entrypoint

因此当前版本**不支持**第三方 Addon，原有注册方式（`onSetup()`、`registerModule()`、
`ModuleManager.registerAddonModule(...)`、`epsilon:addon` entrypoint）都已失效。

如需恢复该能力，需要先重新引入上述包与管理器、恢复两个平台的收集入口，
然后重新编写本文档。模块开发请见 [模块与设置](development/modules-and-addons.md)。
