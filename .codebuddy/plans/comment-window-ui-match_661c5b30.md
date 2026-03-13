---
name: comment-window-ui-match
overview: 按提供截图风格调整评论窗口的布局、配色与交互元素样式，使其与目标设计一致。
design:
  architecture:
    framework: html
  styleKeywords:
    - Dark Card
    - High Contrast
    - Compact Spacing
    - Aligned Actions
  fontSystem:
    fontFamily: PingFang SC
    heading:
      size: 14px
      weight: 600
    subheading:
      size: 12px
      weight: 500
    body:
      size: 12px
      weight: 400
  colorSystem:
    primary:
      - "#3B82F6"
      - "#4EA1FF"
    background:
      - "#2B2B2B"
      - "#32363B"
    text:
      - "#E5EAF0"
      - "#C9CDD4"
    functional:
      - "#57D163"
      - "#F47067"
todos:
  - id: scan-ui-entry
    content: 使用 [subagent:code-explorer] 定位评论弹窗的布局与样式入口
    status: completed
  - id: normalize-style-tokens
    content: 统一颜色、圆角、边距与字体常量以匹配截图
    status: completed
    dependencies:
      - scan-ui-entry
  - id: update-container-layout
    content: 调整弹窗容器与滚动区域尺寸、内边距与背景
    status: completed
    dependencies:
      - normalize-style-tokens
  - id: revise-thread-card
    content: 更新评论卡片标题行、状态行与操作区的对齐与间距
    status: completed
    dependencies:
      - normalize-style-tokens
  - id: refine-composer
    content: 统一新增评论与回复输入区的边框、背景与按钮样式
    status: completed
    dependencies:
      - normalize-style-tokens
  - id: polish-spacing
    content: 优化卡片间距、分隔线与折叠按钮的视觉一致性
    status: completed
    dependencies:
      - revise-thread-card
      - refine-composer
  - id: validate-states
    content: 检查空态、已解决与回复展开状态的视觉一致性
    status: completed
    dependencies:
      - polish-spacing
---

## Product Overview

评论弹窗整体改为深色卡片风格，布局与间距更紧凑，视觉层级清晰，对齐方式与截图一致。

## Core Features

- 评论卡片的标题、内容、状态与操作按钮统一暗色风格与对齐
- 回复与新增评论输入区采用一致的边框、背景与按钮样式
- 评论列表滚动区域与卡片间距、分隔线、折叠按钮样式与截图匹配

## Tech Stack

- Kotlin + IntelliJ Platform SDK
- Swing/JBUI/JBColor 组件体系

## Implementation Details

### Modified Files

```
/Users/zhangbo/CodeBuddy/20260113104520/src/main/kotlin/com/gitee/prviewer/comment/LineCommentManager.kt  # 评论弹窗布局、样式与组件排列
/Users/zhangbo/CodeBuddy/20260113104520/src/main/kotlin/com/gitee/prviewer/comment/LineComment.kt        # 如需扩展显示字段时调整
/Users/zhangbo/CodeBuddy/20260113104520/src/main/kotlin/com/gitee/prviewer/comment/PrManagerPanel.kt     # 如需调整容器尺寸与对齐时修改
```

### Key Code Structures

- **Style Tokens**: 统一颜色、字体、边距与圆角常量，集中在样式函数中复用。
- **Component Builders**: `buildCommentThread`、`buildReplyComposer`、`buildNewRootInputPanel` 中的布局与间距统一调整。
- **Button/Text Styling**: `stylePrimaryButton`、`styleGhostButton`、`styleTextArea`、`styleTextField` 统一匹配截图的视觉规范。

### Technical Implementation Plan

1. 统一弹窗容器与滚动区域背景、内边距与宽高，确保暗色卡片风格稳定。
2. 调整评论卡片的标题行、状态行、回复按钮位置与图标对齐，匹配截图层级。
3. 统一输入区（首条评论/新增评论/回复）的边框、圆角、背景与按钮样式。
4. 优化卡片间距与分隔线，使列表密度与截图一致。
5. 保持现有交互逻辑不变，仅调整 UI 布局与视觉样式。

### Testing Strategy

- 验证评论为空/有评论/已解决状态下的布局一致性
- 验证回复展开/折叠、输入焦点切换与按钮状态显示

## Design Style

暗色高对比卡片式设计，信息层级清晰，操作按钮与文本对齐统一。弹窗为单一面板，不设顶底导航。

## Page Plan

- 评论弹窗（单页）

### 评论弹窗区块

1. 顶部标题区：包含用户头像与标题信息，左对齐，弱分隔线。  
2. 评论列表区：深色卡片纵向排列，卡片间距与阴影轻量化。  
3. 评论卡片区：标题行+状态行+内容区+操作区，层级分明。  
4. 输入与操作区：文本域与按钮一体化布局，右侧按钮对齐。  
5. 空状态区：无评论时展示输入卡片与提示文案。

## Agent Extensions

### SubAgent

- **code-explorer**
- Purpose: 搜索并定位评论弹窗相关的布局与样式构建点
- Expected outcome: 明确需要调整的构建函数与样式入口