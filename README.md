# RookieCrates

RookieCrates 是面向 Paper 1.21.1、ModelEngine R4.1.0 的模型宝箱插件，使用 SQLite 保存宝箱、奖池、玩家钥匙、保底进度、开箱事务和场景恢复数据。当前支持单抽与七连抽，单抽播放 `open1`、七连抽播放 `open7`；玩家可右键公共宝箱模型，也可主手直接右键对应实体钥匙进入抽取 GUI。每个奖品可通过物品内容 GUI 配置最多 27 组物品，并采用 `S、A、B、C` 四档，按等级自动显示黄、粉、蓝、白四种 Loot 模型。

管理员可使用 `/rc config <宝箱ID>` 打开场景点位 GUI，通过左键记录、右键传送和 `Shift + 左键` 精细调整 `CRATE`、`CAMERA`、`LOOT_1..LOOT_7`。
`/rc preview <宝箱ID>` 会按奖池权重随机生成七个私有 Loot 模型，用于检查七个展示点及四档配色，不会触发消费、保底或奖励发放。

## 项目结构

```text
RookieCrates/
├─ src/main/java/com/cuzz/rookieCrates/
│  ├─ command/       命令入口
│  ├─ config/        默认配置读取与校验
│  ├─ domain/        SQLite 领域数据模型
│  ├─ economy/       Vault 经济适配
│  ├─ gui/           玩家和管理员 GUI
│  ├─ key/           实体钥匙标记与统计
│  ├─ listener/      Bukkit 生命周期与交互事件
│  ├─ runtime/       宝箱模型、七点演出和恢复
│  ├─ service/       开箱、付款、保底、发奖和导入导出
│  ├─ storage/       SQLite、DAO 与数据库迁移
│  ├─ util/          物品序列化和消息工具
│  └─ RookieCrates.java
├─ src/main/resources/
│  ├─ config.yml     全局设置及新宝箱默认值
│  ├─ plugin.yml     命令、权限和插件依赖
│  └─ db/            SQLite 迁移脚本
├─ src/test/         单元测试与契约测试
├─ model-assets/     统一命名的 ModelEngine 蓝图
├─ src/assembly/     可部署服务器压缩包规则
├─ 配置.md           服主配置手册
└─ 实机配置与测试.md 测试服部署与验收手册
```

## 构建

项目需要 Java 21 和 Maven。`libs/ModelEngine-4.1.0.jar` 只用于编译 ModelEngine API，不会被打进 RookieCrates。

```text
mvn clean package
```

构建完成后：

- `target/RookieCrates-1.0-SNAPSHOT.jar`：插件本体。
- `target/RookieCrates-1.0-SNAPSHOT-server-package.zip`：服务器部署包，包含插件 JAR、默认 `config.yml`、五个 `.bbmodel` 和文档。

部署包可以直接解压到服务器根目录；必须另外安装完整的 ModelEngine R4.1.0。金币价格大于 `0` 时还需要 Vault 和经济插件。

详细配置步骤见 [`配置.md`](配置.md)，实机验收流程见 [`实机配置与测试.md`](实机配置与测试.md)，模型清单见 [`model-assets/README.md`](model-assets/README.md)。
