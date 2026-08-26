# RookieCrates ModelEngine 模型包

这里保存 RookieCrates 当前场景需要的 ModelEngine R4.1.0 蓝图。此目录是源码仓库中唯一的模型源目录，Maven 构建服务器部署包时会自动将这些文件放入 `plugins/ModelEngine/blueprints`。

## 模型 ID

| 模型 ID | 文件 | 用途 | 必需动画/骨骼 |
| --- | --- | --- | --- |
| `default_crate` | `ModelEngine/blueprints/default_crate.bbmodel` | 默认宝箱 | `idle`、`open1`、`open7` |
| `loot_white` | `ModelEngine/blueprints/loot_white.bbmodel` | C 级白色奖励展示 | `idle`、`ih_item` |
| `loot_blue` | `ModelEngine/blueprints/loot_blue.bbmodel` | B 级蓝色奖励展示 | `idle`、`ih_item` |
| `loot_pink` | `ModelEngine/blueprints/loot_pink.bbmodel` | A 级粉色奖励展示 | `idle`、`ih_item` |
| `loot_yellow` | `ModelEngine/blueprints/loot_yellow.bbmodel` | S 级黄色奖励展示 | `idle`、`ih_item` |

`ih_item` 中的 `ih_` 会让 ModelEngine 4.1 注册持物行为，导入后运行时骨骼 ID 为 `item`。RookieCrates 将其显示变换统一设为 `FIXED`，通过 `HeldItem#setItemProvider` 挂载真实奖励。每个奖品的显示比例都保存在奖品自身配置中，可在奖品编辑 GUI 内单独调整；`config.yml` 的 `loot-display.default-item-scale` 只是新建奖品的默认值。不要将 `ih_item` 改为没有行为的普通空骨骼 `item`。`tag_name` 不是必需骨骼；模型额外提供带 NAMETAG 行为的 `tag_name` 时，插件才会显示名称标签。

## 原文件名映射

| 整理后 | 原文件名 |
| --- | --- |
| `default_crate.bbmodel` | `Xi_crate1.bbmodel` |
| `loot_white.bbmodel` | `抽奖效果白色.bbmodel` |
| `loot_pink.bbmodel` | `抽奖效果粉红.bbmodel` |
| `loot_blue.bbmodel` | `抽奖效果蓝色.bbmodel` |
| `loot_yellow.bbmodel` | `抽奖效果黄色.bbmodel` |

## 部署

1. 推荐运行 `mvn clean package` 后，将生成的 `*-server-package.zip` 直接解压到服务器根目录。
2. 手动部署时，将本目录下 `ModelEngine/blueprints` 中的五个 `.bbmodel` 文件复制到服务端的 `plugins/ModelEngine/blueprints`。
3. 确认完整的 ModelEngine R4.1.0 插件位于服务器真正的 `plugins` 根目录。
4. 重启服务端，让 ModelEngine 构建模型缓存和资源包。
5. RookieCrates 默认使用 `default_crate`，并按 `C → loot_white`、`B → loot_blue`、`A → loot_pink`、`S → loot_yellow` 自动选择奖励模型。映射可在 `config.yml` 的 `defaults.crate.models.loot-by-rarity` 修改。

不要放入 `plugins/plugins` 之类的嵌套目录，否则 Paper 不会加载对应插件。
