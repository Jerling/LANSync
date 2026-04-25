# LANSync 局域网照片同步方案

## 项目概述

当手机和电脑连接到同一 WiFi 时，手机自动将照片同步到电脑指定目录。

**核心特点：**
- 手机主动推送模式（节省电脑资源）
- WiFi 连接时触发，断开时自动退出（省电）
- JWT 登录验证（安全）
- 电脑端被动监听，无轮询

---

## 系统架构

```
┌─────────────────┐         WiFi          ┌─────────────────────┐
│   Android App   │                       │   电脑端 (WSL)       │
│                 │                       │                     │
│  WiFi检测服务    │ ──── POST /api/login─►│   Flask 服务器       │
│  (ConnectivityManager)│ ◄─── token ─── │   端口: 8765        │
│                 │                       │                     │
│  照片扫描器      │ ── POST /api/upload/photo│                   │
│  上传器         │ ◄─── 响应 ─────────── │   目标目录:         │
│                 │                       │   /mnt/d/Photos     │
│  WiFi断开检测   │                       │                     │
│  → 停止服务     │                       │                     │
│  → 退出App     │                       │                     │
└─────────────────┘                       └─────────────────────┘
```

---

## 技术选型

### 电脑端
| 组件 | 技术 | 说明 |
|------|------|------|
| 服务器 | Python 3 + Flask | WSL 原生运行 |
| 认证 | PyJWT | Token 生成验证 |
| 端口 | 8765 | 局域网监听 |

### 手机端
| 组件 | 技术 | 说明 |
|------|------|------|
| App | Kotlin + Jetpack Compose | 原生 Android |
| 网络 | Retrofit + OkHttp | HTTP 客户端 |
| 本地存储 | Room | 同步记录数据库 |
| WiFi 检测 | ConnectivityManager | 系统 API |
| 依赖注入 | Hilt | - |

---

## 已完成功能

### Phase 1: 电脑端服务器 ✅ (95%)

- [x] Flask 基础框架
- [x] JWT 用户认证
- [x] 照片上传接口 (`/api/upload/photo`)
- [x] 批量上传接口 (`/api/upload/batch`)
- [x] 同步状态接口 (`/api/sync/status`)
- [x] 增量同步接口 (`/api/sync/existing`)
- [x] 按日期归档存储
- [x] 日志记录
- [x] 视频文件支持 (.mp4, .mov, .avi, .mkv, .webm, .3gp, .mts)
- [x] 图片文件支持 (.jpg, .jpeg, .png, .gif, .bmp, .webp, .heic, .heif)
- [x] 文件类型统计（图片/视频数量）
- [x] 配置路径修正 (`/mnt/d/Photos`)

### Phase 2: Android App ✅ (70%)
- [x] 项目搭建（Kotlin + Jetpack Compose）
- [x] 登录模块（LoginScreen + LoginViewModel）
- [x] 主界面（HomeScreen + HomeViewModel）
- [x] 设置界面（SettingsScreen + SettingsViewModel）
- [x] WiFi 检测服务（WifiSyncService）
- [x] 照片扫描模块（SyncPhotosUseCase）
- [x] 上传模块（SyncRepository + Retrofit）
- [x] Token 管理（TokenManager + DataStore）
- [x] 本地同步记录数据库（Room）
- [ ] 完整测试
- [ ] 权限处理完善

### Phase 3: WiFi 智能触发
- [ ] WiFi SSID 监听
- [ ] 断开检测 + 自动退出
- [ ] 后台常驻服务

### Phase 4: 进阶功能
- [ ] 视频文件支持
- [ ] 增量同步
- [ ] 断点续传
- [ ] iOS 扩展

---

## API 接口

### 认证
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/login` | 用户登录，返回 JWT Token |
| GET | `/api/user/info` | 获取当前用户信息（需 Token） |

### 同步
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/sync/status` | 获取同步状态 |
| POST | `/api/upload/photo` | 上传单张照片 |
| POST | `/api/upload/batch` | 批量上传照片 |

### 健康检查
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 服务健康检查 |
| GET | `/` | 首页信息 |

---

## 文件存储规则

**目标目录：** `/mnt/d/Photos`

**归档结构：**
```
Photos/
└── 2024/
    └── 01/
        └── 15/
            ├── IMG_20240115_123456.jpg
            └── VID_20240115_654321.mp4
```

**命名规则：** `时间戳_随机ID.扩展名`

---

## 配置说明

### 电脑端 (`Server/config.yaml`)
```yaml
server:
  host: "0.0.0.0"    # 监听所有网卡
  port: 8765

auth:
  jwt_secret: "your-secret-key"
  token_expire_hours: 720  # 30天

storage:
  base_dir: "/mnt/d/Photos"  # 修改为目标目录
  max_file_size_mb: 500

users:
  - username: "photosync"
    password: "sync123"  # 生产环境修改
```

---

## WiFi 触发流程

```
[App 启动]
    ↓
[检查 WiFi 状态]
    ↓
┌─── 未连接指定 SSID ───→ [等待 + 轮询 WiFi]
│
├─── 连接指定 SSID ──→ [自动触发同步流程]
│                           ↓
│                      [登录认证]
│                           ↓
│                      [扫描照片]
│                           ↓
│                      [上传新照片]
│                           ↓
│                      [同步完成]
│                           ↓
└──────────┬─────────────[监听 WiFi 断开]
           ↓
    [WiFi 断开]
           ↓
    [停止服务]
           ↓
    [退出 App]
```

---

## 待确认问题

1. **目标目录**：`/mnt/d/Photos` 是否正确？
2. **默认用户**：`photosync / sync123` 是否接受？
3. **视频支持**：需要同步 .mp4 视频吗？
4. **iOS 扩展**：后续是否需要 iOS App？

---

## 开发时间预估

| Phase | 内容 | 预估时间 |
|-------|------|---------|
| Phase 1 | 电脑端服务器 | ~2h（已完成85%）|
| Phase 2 | Android App 基础 | ~2-3天 |
| Phase 3 | WiFi 智能触发 | ~1-2天 |
| Phase 4 | 进阶功能 | ~1-2天 |

---

*最后更新：2026-04-20*
