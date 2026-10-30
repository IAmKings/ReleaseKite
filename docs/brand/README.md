# ReleaseKite brand

品牌源文件与使用说明。

## 源图标

Sample 应用 launcher icon：

- `sample/src/main/res/drawable/ic_launcher_sample.xml`

本文档目录中的 mark/logo SVG 与 PNG 都由该 Android vector 的图形重绘（同一几何结构、
同一主色 `#2F6FED`），不依赖 Android drawable 运行时。

## 文件

| 文件 | 用途 |
|---|---|
| `releasekite-mark.svg` | 图标 mark（透明背景，正方形） |
| `releasekite-logo.svg` | 主 logo lockup：mark + 英文 wordmark + 副标题（浅色背景） |
| `releasekite-logo-dark.svg` | 深色背景版本 |
| `releasekite-logo-dark-1024.png` | 深色版本 PNG（1024 宽） |
| `releasekite-mark-512.png` | 常用图标/应用头像 512×512 |
| `releasekite-logo-1024.png` | 主 logo PNG，1024 宽 |
| `releasekite-social-1280x640.png` | GitHub social preview / 分享图（1280×640） |

## 用色

| Token | 值 | 用途 |
|---|---|---|
| Kite Blue | `#2F6FED` | 主标记/强调色 |
| Ink | `#0B1B33` | 浅色背景文字 |
| Night | `#0B1220` | 深色背景 |
| Mist | `#62708A` / `#8FA1C0` | 次级文字 |

## 使用建议

- 最小留白：mark 四周至少留出 mark 宽度的 1/4；
- 不要把 mark 拉伸为非等比、不要改变箭头/底托与六边形的相对位置；
- README/文档可直接引用 SVG；应用头像/商店图用 512 PNG；
- GitHub 仓库 social preview 可在 Repository settings 上传 `releasekite-social-1280x640.png`。
