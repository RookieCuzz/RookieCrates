# RookieCrates ModelEngine 模型包

这里保存 RookieCrates 当前场景需要的 ModelEngine R4 蓝图。此目录是源码仓库中唯一的模型源目录，Maven 构建服务器部署包时会自动将这些文件放入 `plugins/ModelEngine/blueprints`。

## 模型 ID

| 模型 ID | 文件 | 用途 | 必需动画/骨骼 |
| --- | --- | --- | --- |
| `default_crate` | `ModelEngine/blueprints/default_crate.bbmodel` | 默认宝箱 | `idle`、`open2` |
| `loot_white` | `ModelEngine/blueprints/loot_white.bbmodel` | C 级白色奖励展示 | `idle`、`item` |
| `loot_blue` | `ModelEngine/blueprints/loot_blue.bbmodel` | B 级蓝色奖励展示 | `idle`、`item` |
| `loot_pink` | `ModelEngine/blueprints/loot_pink.bbmodel` | A 级粉色奖励展示 | `idle`、`item` |
| `loot_yellow` | `ModelEngine/blueprints/loot_yellow.bbmodel` | S 级黄色奖励展示 | `idle`、`item` |

`tag_name` 不是必需骨骼。RookieCrates 只强制要求 loot 模型存在 `item` 骨骼；如果模型额外提供带 NAMETAG 行为的 `tag_name`，插件才会显示模型名称标签。

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
3. 确认完整的 ModelEngine R4 插件位于服务器真正的 `plugins` 根目录。
4. 重启服务端，让 ModelEngine 构建模型缓存和资源包。
5. RookieCrates 默认使用 `default_crate`，并按 `C → loot_white`、`B → loot_blue`、`A → loot_pink`、`S → loot_yellow` 自动选择奖励模型。映射可在 `config.yml` 的 `defaults.crate.models.loot-by-rarity` 修改。

不要放入 `plugins/plugins` 之类的嵌套目录，否则 Paper 不会加载对应插件。
