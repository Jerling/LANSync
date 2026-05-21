# LANSync 项目交接文档（Nomad Labs）

> 创建时间：2026-05-17  
> 项目负责人：Jer  
> 状态：**交接中（团队协作模式）**

---

## 一、项目概述

**LANSync** 是一个局域网照片/视频同步工具，支持：
- Android App（手机端） → 手动/定时将照片同步到电脑
- Flask Server（电脑端） → 接收并存储照片，按日期归档
- Web App（可选） → 浏览器浏览云端相册

**核心技术栈：**
- Android：Kotlin + Jetpack Compose + Hilt + Retrofit + Room
- 后端：Python 3 + Flask + PyJWT + Flask-Limiter
- Web：Vue 3 + Vite

---

## 二、项目结构

```
/home/jer/data/Code/LANSync/
├── App/                    # Android App（主项目）
│   └── app/src/main/java/com/lansync/app/
│       ├── data/           # ApiClient, Repository, Room DAOs
│       ├── domain/         # UseCase, Models, SyncState
│       ├── service/        # WifiSyncService（前台服务）
│       └── ui/             # Compose 界面（home, gallery, settings, login）
├── Server/                 # Flask 服务器
│   ├── app.py              # 入口
│   ├── handlers.py         # 所有 API 路由
│   ├── storage.py          # PhotoStorage，文件操作核心
│   ├── auth.py             # JWT 认证
│   ├── config.yaml         # 配置文件
│   └── tests/              # 单元测试
├── Web/                    # Vue 3 Web App（已部署）
│   └── src/views/GalleryView.vue  # 云相册浏览界面
└── DESIGN.md               # 详细设计文档
```

**服务器部署路径：** `/home/jer/data/Code/LANSync/Server/`

**照片存储路径：** `/home/jer/data/Photos/`（NUTSTORE 同步盘）

**用户目录隔离：** `/home/jer/data/Photos/{username}/`

---

## 三、测试环境

| 项目 | 值 |
|------|-----|
| 服务器地址 | `http://192.168.0.108:8765` |
| 测试账号 | `photosync / sync123` |
| Web App | `http://192.168.0.108.8765/static/`（Linux Mint 部署） |
| Android 设备 | OPPO PKX110，Android 16 |
| 开发机器 | WSL2 Ubuntu，端口 8765 映射到 Windows |

**服务器启动命令：**
```bash
pkill -f "python.*app.py" && cd /home/jer/data/Code/LANSync/Server && python3 app.py
```

**服务器限流配置（已更新）：**
- `/api/upload/photo` → 200/minute
- `/api/upload/resume/chunk` → 200/minute
- `/api/login` → 5/minute

---

## 四、已知问题（Kanban 上有任务卡）

| # | 问题 | 优先级 | 负责人 |
|---|------|--------|--------|
| T-1 | 云相册数量与实际文件数不符（手机644 vs API返回651 vs 实际1265） | 高 | android-dev |
| T-2 | OPPO 后台冻结导致同步中断 | 高 | android-dev |
| T-3 | Server 单线程瓶颈评估 | 中 | backend-dev |
| T-4 | Web App 选择120+照片卡死问题验证 | 中 | backend-dev |

**Kanban 地址：** `~/.hermes/kanban.db`

---

## 五、关键文件索引

### Android App

| 文件 | 职责 |
|------|------|
| `GalleryViewModel.kt` | 云相册列表加载，监听同步状态刷新 |
| `WifiSyncService.kt` | 前台 Service，照片扫描+上传核心 |
| `SyncPhotosUseCase.kt` | 照片扫描、去重、上传 Flow |
| `ApiClient.kt` | Retrofit HTTP 客户端配置 |
| `TokenManager.kt` | JWT Token 存储（DataStore） |
| `SyncedFileDao.kt` | Room 数据库，同步记录 |
| `GalleryScreen.kt` | 云相册 Compose 界面 |

### Flask Server

| 文件 | 职责 |
|------|------|
| `app.py` | Flask 入口，限流、跨域配置 |
| `handlers.py` | 所有 API 路由实现 |
| `storage.py` | PhotoStorage 类，文件读写、缩略图、manifest |
| `auth.py` | JWT 创建、验证、用户认证 |

---

## 六、最近修改记录

| 日期 | 修改内容 |
|------|---------|
| 2026-05-17 | 修复：同步完成后云相册自动刷新（GalleryViewModel 监听 SyncState） |
| 2026-05-17 | 更新：服务器限流配置，上传接口从全局50/h提升到200/min |
| 2026-05-11 | 修复：Web App 选择120+照片卡死（CSS-only 模式+延迟读取） |
| 2026-05-11 | 修复：照片/视频上传后日期不对（iPhone EXIF tag 306） |

---

## 七、团队成员 Profile（Nomad Labs）

| Profile | 角色 | 主要职责 |
|---------|------|---------|
| `ops` | 运维工程师 | 接收用户问题，创建 GitCode Issue，跟踪状态，反馈给 PM |
| `pm` | 产品经理 | 决策优先级，拒绝需求必须给理由，排版本计划 |
| `architect` | 架构师 | 分析技术方案，与开发+QA三方讨论达成一致后输出方案 |
| `research` | 研究院 | 探索有竞争力的方向，给 PM 提建议（PM 拒绝必须给理由） |
| `security` | 安全专家 | 审核架构方案，输出安全意见 |
| `qa-lead` | 测试专家/质量审计 | 参与架构讨论，全量测试，质量审计，联合 PM 评审发布 |
| `android-dev` | Android 开发 | Android App 开发 |
| `backend-dev` | 后端开发 | Flask Server 开发 |
| `qa` | 测试工程师 | 功能测试、回归测试 |

---

## 八、工作流程（完整）

```
用户（你）
  ↓ 报问题/反馈
ops（运维）
  ↓ 创建 GitCode Issue，反馈给
pm（产品经理）
  ↓ 决策做/不做（不做必须给理由）→ 分发给
architect（架构师）
  ↓ 分析方案，与开发+QA讨论，三方达成一致 → 提交
security（安全专家）
  ↓ 安全审核通过 → 开发按计划实现（自验证）→ 测试
qa-lead / qa
  ↓ 全量测试通过 → 联合评审（pm + qa-lead）
  ↓ 评审通过 → 发布版本
```

**研究院独立工作流：**
```
research（研究院）
  ↓ 发现竞争力机会点
pm（产品经理）
  ↓ 决策：做（排入版本）/ 不做（必须给理由）
  ↓ 做 → 分发给 architect 继续正常流程
```

---

## 九、GitCode 项目管理

- **项目地址**：https://gitcode.com/Jerling/LANsync
- **Issue 列表**：ops 负责创建和维护
- **标签规范**：P0（严重）/ P1（重要）/ P2（一般）/ P3（优化）/ bug / feature / security

---

## 十、版本管理（PM 负责）

| 版本 | 状态 | 包含内容 |
|------|------|---------|
| v1.0 | ✅ 完成 | MVP 基础功能 |
| v1.1 | 🟡 进行中 | 稳定性修复：云相册数量不符、OPPO 冻结 |
| v1.2 | 📋 规划中 | 进度透明度、断点续传优化 |
| v2.0 | ❓ 待定 | iOS App、主动发现等 |

---

## 十一、联系方式

- 项目 Owner：Jer（**用户/客户角色**，只负责使用和反馈问题，不再参与项目管理）
- 团队沟通：通过 Kanban 任务 + Profile 协作