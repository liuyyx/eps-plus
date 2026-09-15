# Epsilon · eps-plus

> Minecraft **26.2** 多加载器辅助客户端（Fabric / NeoForge）。
> 本仓库是 [NekoyaHouse/Epsilon](https://github.com/NekoyaHouse/Epsilon) 的分支，在保持上游架构不变的前提下，额外移植了几个模块。

<p align="left">
  <img alt="MC" src="https://img.shields.io/badge/Minecraft-26.2-4c1?style=flat-square">
  <img alt="Loaders" src="https://img.shields.io/badge/loaders-Fabric%20%7C%20NeoForge-6a5acd?style=flat-square">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-GPLv3-blue?style=flat-square"></a>
</p>

## 📦 下载

编译好的 jar 见 [**Releases**](../../releases)。

| 加载器 | 文件 |
|---|---|
| Fabric | `epsilon-fabric-26.2-*.jar` |
| NeoForge | `epsilon-neoforge-26.2-*.jar` |

按你整合包使用的加载器二选一，放进 `mods/` 即可。**不要两个都放。**

## ✨ 相对上游新增的模块

### Telly（移动 · 自动搭路）
自动 telly 搭桥。启用后按提示完成"武装"手势即可接管：

1. 站到方块边缘，**低头**（≥75°）并**朝向正对斜角**（45°+k·90°）
2. **按住潜行** —— 屏上会出现一个高亮框，显示需要对准的方块面区域
3. 提示变绿后，**按住右键并松开潜行** → 开始自动搭桥

屏幕提示会写明当前手势还差哪一步。设置里的 **激活诊断** 打开后，按住潜行时每秒会输出一次探测结果（九项判据 + 触发状态），可用于排查为什么无法触发。

### Scaffold · Legit（移动 · 蹲起搭）
蹲起搭模式，带边缘状态机（踏上边缘 → 潜行等待 → 允许放置），以及：
- **Place Delay / Place Delay Random** —— 放置节流，避免逐刻连续放置形成的规律时序
- **Legit Sneak Delay / Legit Sneak Random** —— 蹲起时长及其随机分量

> 转向尚未到位时不会放置。否则放置包发出时的朝向与服务器所见不一致，会被服务器丢弃（单机测不出来，联机表现为"吞方块"）。

### KillAura · AI 转头（战斗）
由捆绑的 LiquidBounceNG 战斗回归模型（`21KC11KP` / `19KC8KP`）直接输出每刻 yaw/pitch 增量，取代普通模式的"直接设定目标角度"。纯 Java 推理，无原生依赖。

- **Aim Mode**：`Normal` / `Ai`
- **AI Model**：选择回归模型
- **AI Yaw/Pitch Multiplier**：输出缩放（默认 1.5 / 1.0）

> 模型学的是人类鼠标轨迹，每刻只走 0.14°~9.2°，因此转向是渐进的而非瞬发 —— 这与常规模式 180°/刻 的手感差异很大，属设计预期。想要更快请调 `AI Yaw Multiplier`。

## 🎨 渲染系统

上游的 Lumin 渲染系统提供自定义管线：矩形与圆角矩形、阴影与模糊、TTF 字体、纹理、自定义顶点格式。

详见 [Lumin Graphics README](common/src/main/java/com/github/epsilon/graphics/README.md)，
声明式 UI 层见 [Epsilon GUI Library 指南](docs/gui-library.md)。

## ⚙️ 构建

需要 **JDK 21+**：

```bash
./gradlew build
```

产物位于 `fabric/build/libs/` 与 `neoforge/build/libs/`。

开发运行：

```bash
./gradlew runClient
```

## 📁 仓库结构

```
common/     # 与加载器无关的核心：模块、渲染、GUI、事件总线
fabric/     # Fabric 入口
neoforge/   # NeoForge 入口
docs/       # 开发文档
```

## 🙏 致谢

本项目的核心来自 [NekoyaHouse/Epsilon](https://github.com/NekoyaHouse/Epsilon)，遵循原项目的 GPL-3.0 许可。
第三方代码归属详见 [NOTICE](NOTICE.md)。

上游同时还受益于：
- [Meteor Client](https://github.com/MeteorDevelopment/meteor-client)
- [Orbit](https://github.com/MeteorDevelopment/orbit)
- [LeavesHack](https://github.com/MrBZBZ/LeavesHack)
- [TrollHack](https://github.com/Luna5ama/TrollHack)

## 👥 贡献者

新增模块的算法与数据来源，以及本次移植工作：

- [**@OlziYT**](https://github.com/OlziYT) —— [RavenBS-Plus-Plus](https://github.com/OlziYT/RavenBS-Plus-Plus)
  Telly（自动搭路）的算法来源：边缘判据、21 帧旋转脚本、方块搜索与放置策略
- [**@woshijiejue**](https://github.com/woshijiejue) —— [Leader-Lite](https://github.com/woshijiejue/Leader-Lite)
  Scaffold · Legit（蹲起搭）的算法来源：边缘状态机、转向限速与放置闸门
- [**@minecrafttzh**](https://github.com/minecrafttzh) —— [OpenVape4.21](https://github.com/minecrafttzh/OpenVape4.21)
  KillAura AI 转头的算法与回归模型来源
- [**DeepSeek**](https://www.deepseek.com/) —— 上述模块到 Minecraft 26.2 / Epsilon 的移植、
  平台 API 适配（1.8.9 → 26.2）、反作弊相关排查与本文档

## 📝 许可证

本项目与上游一致，采用 [GNU General Public License v3.0](LICENSE)。

---

上游版权 © 2026 NekoyaHouse。本分支的修改部分同样以 GPL-3.0 发布。
