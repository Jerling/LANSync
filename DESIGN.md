# LANSync 局域网照片同步方案

## 项目概述

手机手动或定时将照片同步到电脑指定目录。

**核心特点：**
- 手动或定时触发同步（灵活可控）
- 局域网安全传输（JWT 验证）
- 手机主动推送模式（节省电脑资源）
- 电脑端被动监听，无轮询

---

## 系统架构

```
┌─────────────────┐                       ┌─────────────────────┐
│   Android App   │      局域网           │   电脑端 (WSL)       │
│                 │                       │                     │
│  照片管理服务    │ ─── POST /api/login─►│   Flask 服务器       │
│  (手动/定时触发) │ ◄─── token ───       │   端口: 8765        │
│                 │                       │                     │
│  照片扫描器      │ ── POST /api/upload/photo│                   │
│  上传器         │ ◄─── 响应 ─────────── │   目标目录:         │
│                 │                       │   /mnt/d/Photos     │
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
| 依赖注入 | Hilt | - |

---

## 已完成功能

### Phase 1: 电脑端服务器 ✅ (完成)

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
- [x] 断点续传（分片上传，1MB/块，支持网络中断后从断点恢复）

### Phase 2: Android App ✅ (90%)

- [x] 项目搭建（Kotlin + Jetpack Compose）
- [x] 登录模块（LoginScreen + LoginViewModel）
- [x] 主界面（HomeScreen + HomeViewModel）
- [x] 设置界面（SettingsScreen + SettingsViewModel）
- [x] 照片扫描模块（SyncPhotosUseCase）
- [x] 上传模块（SyncRepository + Retrofit）
- [x] Token 管理（TokenManager + DataStore）
- [x] 本地同步记录数据库（Room）
- [ ] 完整测试
- [ ] 权限处理完善

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
| POST | `/api/upload/resume/init` | 初始化分片上传 |
| POST | `/api/upload/resume/chunk` | 上传分片数据 |
| POST | `/api/upload/resume/complete` | 完成分片上传 |
| GET | `/api/upload/resume/status` | 查询分片状态 |
| POST | `/api/upload/resume/cancel` | 取消分片上传 |

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

## 已知问题与解决方案

### App OOM 问题
- **现象**：上传大文件时 App 崩溃（OOM）
- **原因**：`inputStream.readBytes()` 全量加载文件到内存 + HttpLoggingInterceptor BODY 模式双重占用
- **解决**：改用流式上传，不过滤已知文件，禁用 BODY 级日志

### OPPO 系统后台冻结
- **现象**：Hans Freezer 会冻结后台 App 协程
- **解决**：使用前台 Service + 通知栏保活

### Server 进度条卡住
- **现象**：上传进度条长时间不动
- **原因**：多为网络慢正常现象，非 bug
- **解决**：已优化进度反馈逻辑

---

## 待确认问题

1. **目标目录**：`/mnt/d/Photos` 是否正确？
2. **默认用户**：`photosync / sync123` 是否接受？
3. **iOS 扩展**：后续是否需要 iOS App？

---

## 开发时间预估

| Phase | 内容 | 预估时间 |
|-------|------|---------|
| Phase 1 | 电脑端服务器 | ~2h（已完成100%）|
| Phase 2 | Android App 基础 | ~2-3天（已完成80%）|
| Phase 3 | WiFi 智能触发 | 已取消 |
| Phase 4 | 进阶功能 | 已取消（功能已在 Phase 1 实现）|

---

*最后更新：2026-04-26*
