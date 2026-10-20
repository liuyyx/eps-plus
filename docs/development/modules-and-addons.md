# 模块与 Addon

## Module 基本模式

本体功能模块继承 `Module`。现有模块通常使用单例和私有构造函数：

```java
public class MyModule extends Module {

    public static final MyModule INSTANCE = new MyModule();

    private final SettingGroup sgGeneral = settingGroup("General");
    private final BoolSetting enabledOption = boolSetting("Enabled Option", true).group(sgGeneral);
    private final DoubleSetting range = doubleSetting(
            "Range", 3.0, 1.0, 6.0, 0.1,
            enabledOption::getValue
    ).group(sgGeneral);

    private MyModule() {
        super("My Module", Category.COMBAT);
    }

    @Override
    protected void onEnable() {
    }

    @Override
    protected void onDisable() {
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (nullCheck()) return;
    }
}
```

`Module.setEnabled(true)` 会先订阅事件、发送通知，再调用 `onEnable()`；禁用时先取消订阅、发送通知，
再调用 `onDisable()`。

`Module.resetCustomState()`、`saveCustomState()`、`loadCustomState(JsonObject)` 用于 Setting 之外的持久化
状态。`setDefaultEnabled()` 和 `setDefaultHidden()` 同时影响 `reset()` 行为；普通模块默认 disabled、hidden。

键位默认值为 `-1`。`Module.BindMode.Toggle` 在按下时切换，`Hold` 在按下时启用、松开时禁用。鼠标键由
`KeybindUtils` 编码。

## Setting DSL

`Module` 实现 `SettingHost`，提供统一的设置 DSL，并支持适用类型的 `onChanged` 重载。

可用设置：

- `boolSetting`、`intSetting`、`doubleSetting`、`enumSetting`、`colorSetting`
- `stringSetting`、`stringListSetting`、`keybindSetting`、`buttonSetting`
- `blockListSetting`、`itemListSetting`、`entityTypeListSetting`
- `enchantmentListSetting`、`soundEventListSetting`

完整重载以 `SettingHost.java` 为准。依赖类型为 `Setting.Dependency`；返回 `false` 时设置不可用且不可见：

```java
private final BoolSetting advanced = boolSetting("Advanced", false);
private final IntSetting threshold = intSetting(
        "Threshold", 50, 0, 100, 1,
        advanced::getValue,
        value -> refresh(value)
);
```

相关能力：

- `settingGroup(name)` 在顶层按名称忽略大小写复用分组。
- `SettingGroup.child(name)` 在父分组下按名称忽略大小写复用子分组，可以继续嵌套；子分组只属于创建它的
  父分组，父子关系决定 GUI 缩进和翻译 key 层级。
- `.group(group)` 仅指定 GUI 分组（可以是任意层级的子分组），不负责注册 Setting。
- `.rootSetting()` 表示值由根配置单独持久化；当前 `ClientSetting.showWelcomeScreen`、`WorldTweaks`
  的雾与时间设置使用它。
- `.applyWhenRelease()` 表示滑动或编辑结束后再应用昂贵更新。
- `Setting.isAvailable()` 的语义由 dependency 决定；DSL 默认传入恒真的 dependency。

嵌套分组示例；父分组内直接 Setting 与首次出现的子分组按声明顺序交错渲染：

```java
private final SettingGroup sgWeapon = settingGroup("Weapon");
private final SettingGroup sgEnchants = sgWeapon.child("Enchants");
private final SettingGroup sgSword = sgEnchants.child("Sword");

private final BoolSetting autoSwitch = boolSetting("Auto Switch", true).group(sgWeapon);
private final IntSetting minLevel = intSetting("Min Level", 1, 1, 5, 1).group(sgSword);
```

## Addon

Addon 系统已移除（提交 675fe80），当前版本不支持第三方 Addon。详情见 [Addon 开发](../addon-development.md)。
