---
name: gitee-pr-manager-plugin
overview: 为 IntelliJ IDEA 2024.1 开发基于 Gradle + Kotlin 的本地 PR 查看 MVP 插件,使用 git4idea 实现分支对比与 Diff,并在工具窗口通过下拉选择源/目标分支进行查看。
design:
  architecture:
    framework: html
  styleKeywords:
    - Modern
    - Minimal
    - High-contrast
    - Tool-window
  fontSystem:
    fontFamily: PingFang SC
    heading:
      size: 18px
      weight: 600
    subheading:
      size: 14px
      weight: 500
    body:
      size: 12px
      weight: 400
  colorSystem:
    primary:
      - "#2F6FED"
      - "#1F3A8A"
    background:
      - "#F5F7FB"
      - "#FFFFFF"
    text:
      - "#111827"
      - "#6B7280"
    functional:
      - "#16A34A"
      - "#DC2626"
      - "#F59E0B"
todos:
  - id: toolwindow-ui
    content: 实现工具窗口与分支下拉选择区域
    status: completed
  - id: branch-service
    content: 实现本地分支读取与列表刷新
    status: completed
    dependencies:
      - toolwindow-ui
  - id: compare-service
    content: 实现分支对比并输出变更列表
    status: completed
    dependencies:
      - branch-service
  - id: change-list-ui
    content: 在工具窗口展示变更列表并支持选择
    status: completed
    dependencies:
      - compare-service
  - id: diff-view
    content: 集成 Diff 视图展示选中文件差异
    status: completed
    dependencies:
      - change-list-ui
  - id: error-state
    content: 补充无仓库/无变更/失败的提示状态
    status: completed
    dependencies:
      - toolwindow-ui
  - id: 1ccfbfad
    content: 7. 对比视图中，添加行评论按钮，点击按钮后，弹出一个评论窗口；
    status: completed
---

## Product Overview

本地 PR 查看 MVP 插件：在 IntelliJ IDEA 2024.1 中通过工具窗口选择源/目标分支，基于本地 Git 分支对比展示变更与 Diff。

## Core Features

- 工具窗口入口，提供源/目标分支下拉选择与刷新
- 基于 git4idea 的分支对比与变更列表展示
- 选中文件后显示 Diff 视图与变更详情
- 轻量错误提示（无分支/无变更/比较失败）

## Tech Stack

- 插件开发：IntelliJ Platform SDK + Kotlin
- 构建工具：Gradle (IntelliJ Plugin)
- Git 对比与 Diff：git4idea
- UI：IntelliJ Platform UI (ToolWindow + Swing)

## Tech Architecture

### System Architecture

- 采用插件模块分层：UI 层（工具窗口）→ 领域服务层（分支与对比）→ git4idea 适配层  

```mermaid
flowchart LR
UI[ToolWindow UI] --> SVC[Compare Service]
SVC --> GIT[git4idea API]
GIT --> SVC --> UI
```

### Module Division

- **ToolWindow UI 模块**：分支选择、变更列表、Diff 入口
- **Branch & Compare Service 模块**：分支读取、比较结果聚合、错误处理
- **git4idea Adapter 模块**：封装 git4idea 调用与结果转换

### Data Flow

```mermaid
flowchart TD
A[用户选择源/目标分支] --> B[读取本地分支列表]
B --> C[执行分支对比]
C --> D[生成变更列表]
D --> E[选择文件打开 Diff]
C --> F{异常?}
F -->|是| G[UI 错误提示]
```

## Implementation Details

### Core Directory Structure

```
project-root/
├── src/main/kotlin/
│   ├── toolwindow/
│   │   ├── PrManagerToolWindow.kt
│   │   └── PrManagerPanel.kt
│   ├── service/
│   │   └── BranchCompareService.kt
│   ├── git/
│   │   └── Git4IdeaAdapter.kt
│   └── model/
│       └── ChangeItem.kt
└── src/main/resources/
    └── META-INF/plugin.xml
```

### Key Code Structures

**ChangeItem 数据结构**：承载文件路径、变更类型与可选的差异信息。

```
data class ChangeItem(
  val filePath: String,
  val changeType: String
)
```

**BranchCompareService 接口**：对外提供分支读取与比较结果。

```
interface BranchCompareService {
  fun listBranches(): List<String>
  fun compare(source: String, target: String): List<ChangeItem>
}
```

### Technical Implementation Plan

1. **Problem**：在工具窗口展示本地分支并支持选择  
**Approach**：读取本地仓库分支列表，填充下拉框
**Steps**：获取仓库→读取分支→刷新 UI
**Testing**：无仓库/空分支场景

2. **Problem**：分支对比与变更列表  
**Approach**：使用 git4idea 执行 compare，转换为 ChangeItem
**Steps**：执行比较→解析结果→列表展示
**Testing**：无差异/大变更量

3. **Problem**：Diff 展示  
**Approach**：基于 git4idea 提供的 Diff 展示入口
**Steps**：选中文件→构建 Diff 请求→打开 Diff 视图
**Testing**：新增/删除/重命名文件

### Integration Points

- UI 与服务层通过方法调用传递数据对象
- git4idea API 返回结果统一转换为 ChangeItem