# 缇喵品牌资源

原创小猫吉祥物：奶油脸、深棕耳朵、鼻口斑、玻璃珠大眼。不包含任何现有 IP 的独有细节。

## 文件

| 文件 | 用途 |
|---|---|
| `docs/brand/mascot.svg` | 完整矢量源，含四种状态 |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | 自适应图标前景（猫头） |
| `app/src/main/res/drawable/ic_launcher_background.xml` | 自适应图标背景（渐变） |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | Android 13 主题图标（单色剪影） |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | 自适应图标入口 |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | 圆形自适应图标入口 |
| `app/src/main/res/drawable/ic_stat_connected.xml` | 通知小图标：已连接 |
| `app/src/main/res/drawable/ic_stat_idle.xml` | 通知小图标：未连接（睡眼） |
| `app/src/main/res/drawable/ic_qs_tile.xml` | 快捷设置磁贴图标 |
| `app/src/main/res/drawable/ic_empty_cat.xml` | 空状态插图：睡着的猫 |

## 配色

| 用途 | 值 |
|---|---|
| 奶油脸 | `#F4E7D7` |
| 耳朵、耳内 | `#8A6448` / `#B48A6A` |
| 轮廓 | `#4A3426` |
| 鼻口斑 | `#A47C5E` |
| 鼻、嘴 | `#3B2A22` |
| 眼珠 | `#1D1822` |
| 眼玻璃下缘 | `#BFE3EF` |
| 高光 | `#FFFFFF` |
| 睡意 z | `#9AA7D6` |
| 泪滴 | `#9FD3FF` |
| 主强调 | `#FF8A5C`（深色模式 `#FFB08F`） |
| 浅色背景 | `#FDF3EC` |
| 深色背景 | `#1B1622` |
| 图标背景渐变 | `#FDF3EC` → `#FFD2B8` |

## 使用规则

- 小猫只用于三处：首页主开关、应用图标、空状态。列表行、头像、通知正文不要重复使用。
- 保持 120:104 比例。不拉伸、不裁切耳朵。
- 深色模式不改猫脸颜色。猫脸保持奶油色。
- 状态只用四种表情：睡、张望、开心、委屈。天线只表示连接状态。
- 不加其它 IP 的独有细节，例如额头小飞虫、刺绣花纹。
- 不使用模糊、滤镜、遮罩。图标只用纯色或线性渐变。

## 图标规格

- 自适应图标：108dp 画布，主体在 66dp 安全圆内；背景为奶油到桃色渐变；含 `monochrome` 标签。
- 单色图标：单色剪影，两只耳朵、两个眼洞。系统负责着色。
- 通知小图标：24dp，纯白加透明，不设底色。
- 快捷设置磁贴：24dp，单色，系统负责着色。
- 空状态：透明底，120x104dp，可直接放在浅色或深色卡片上。

## 导出注意

- VectorDrawable 不支持 `filter`、`blur`、`mask`。本仓库全部用纯色或线性渐变替代。
- VectorDrawable 的椭圆用三次贝塞尔近似，`mascot.svg` 用原始椭圆。两者形状一致。
