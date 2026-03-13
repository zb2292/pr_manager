---
name: pr-compare-line-comments
overview: 在现有本地分支 Diff 基础上增加行号图标触发的评论弹窗与内存态管理,不做持久化。
design:
  architecture:
    framework: react
    component: shadcn
  styleKeywords:
    - Minimalism
    - Clean
    - Subtle Motion
    - Code-Centric
  fontSystem:
    fontFamily: PingFang SC
    heading:
      size: 20px
      weight: 600
    subheading:
      size: 16px
      weight: 500
    body:
      size: 14px
      weight: 400
  colorSystem:
    primary:
      - "#2563EB"
      - "#1D4ED8"
    background:
      - "#0B1220"
      - "#111827"
    text:
      - "#E5E7EB"
      - "#9CA3AF"
    functional:
      - "#22C55E"
      - "#F59E0B"
      - "#EF4444"
todos:
  - id: repo-scan
    content: 使用 [subagent:code-explorer] 梳理 Diff 视图结构与可复用组件
    status: completed
  - id: comment-types
    content: 定义行评论数据结构与文件行号索引规则
    status: completed
    dependencies:
      - repo-scan
  - id: memory-store
    content: 实现内存态评论管理与读写接口
    status: completed
    dependencies:
      - comment-types
  - id: line-icon
    content: 在行号区加入评论图标与悬浮提示
    status: completed
    dependencies:
      - memory-store
  - id: comment-popover
    content: 实现行评论弹窗的展示、输入与删除交互
    status: completed
    dependencies:
      - line-icon
  - id: diff-integration
    content: 将评论状态与 Diff 行渲染联动显示
    status: completed
    dependencies:
      - comment-popover
---

## Product Overview

在现有本地分支 Diff 视图中加入行号图标触发的行评论弹窗，评论数据仅内存保存，关闭即丢。

## Core Features

- 在 Diff 行号旁展示评论触发图标，点击打开对应行评论弹窗
- 评论弹窗支持查看、添加、删除当前行的临时评论
- 内存态管理评论数据，关闭弹窗或刷新后清空
- 评论状态在 Diff 视图中有清晰的可视提示

## Tech Stack

- 沿用现有前端框架与组件库
- 沿用现有状态管理方案实现内存态评论数据
- 与现有 Diff 视图渲染逻辑集成，不新增持久化层

## Tech Architecture

### System Architecture

- 架构模式：基于现有前端架构的视图层 + 状态层
- 组件结构：Diff 视图 → 行号区图标 → 评论弹窗 → 评论列表与输入

```mermaid
flowchart LR
A[Diff 行渲染] --> B[行号图标点击]
B --> C[打开评论弹窗]
C --> D[读取/写入内存评论状态]
D --> A
```

### Module Division

- **Diff 行交互模块**：在行号区渲染图标并处理点击事件  
- **评论弹窗模块**：展示评论列表与输入区，处理新增/删除  
- **评论内存状态模块**：按文件与行号索引管理临时评论数据  

### Data Flow

行号图标点击 → 弹窗打开 → 读取内存评论 → 用户提交/删除 → 更新内存状态 → Diff 视图更新标记

## Implementation Details

### Core Directory Structure

```
project-root/
├── src/
│   ├── components/
│   │   ├── DiffLineCommentIcon.*      # 新增/修改：行号图标
│   │   └── DiffLineCommentPopover.*   # 新增：行评论弹窗
│   ├── services/
│   │   └── diffCommentStore.*         # 新增：内存态评论管理
│   └── types/
│       └── diffComment.*              # 新增：评论数据类型
```

### Key Code Structures

**DiffComment**

- id, filePath, lineNumber, content, author, createdAt

**diffCommentStore**

- getComments(filePath, lineNumber)
- addComment(filePath, lineNumber, content)
- removeComment(filePath, lineNumber, commentId)

## Design Style

采用现代极简风格，突出代码阅读与批注的清晰度。弹窗使用轻量浮层与柔和阴影，图标与行号区保持对齐。交互具备悬浮反馈与微动效，减少对 Diff 主体阅读的干扰。

## Page Planning

- Diff 视图页：行号区图标、评论弹窗、评论状态提示、顶部/底部导航保持一致

## Agent Extensions

### SubAgent

- **code-explorer**
- Purpose: 扫描现有代码结构与 Diff 视图相关实现，定位集成点
- Expected outcome: 输出可复用组件、状态管理位置与修改范围