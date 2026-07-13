# StartLogin

![GitHub release (latest by date)](https://img.shields.io/github/v/release/pingfeng/StartLogin)
![GitHub License](https://img.shields.io/github/license/pingfeng/StartLogin)
![GitHub code size in bytes](https://img.shields.io/github/languages/code-size/pingfeng/StartLogin)

一款专为 **Paper/Folia** 服务端设计的现代化玩家登录认证插件，提供安全可靠的账号管理系统。

## ✨ 特性

### 🔐 安全功能
- **密码强度检测** - 支持大写字母、小写字母、数字、特殊字符要求
- **密码黑名单** - 阻止使用常见弱密码
- **IP登录限制** - 防止同一IP注册大量账号
- **登录尝试记录** - 记录每次登录的时间、IP、成功/失败状态
- **异地登录提醒** - 检测异常IP登录并提示玩家
- **账号锁定** - 多次登录失败后自动锁定账号

### 🎮 用户体验
- **Paper Dialog API** - 使用原生对话框，界面美观流畅
- **动态对话框更新** - 错误提示无需关闭再打开，直接更新内容
- **会话缓存** - 规定时间内免密码快速登录
- **首次登录保护** - 新玩家注册后获得短暂无敌效果
- **登录欢迎语** - 可自定义个性化欢迎消息

### 🔧 管理功能
- **玩家账号信息查询** - `/sl info <玩家名>`
- **在线玩家统计** - `/sl stats`
- **强制修改密码** - `/sl forcechangepwd <玩家名>`
- **密码过期策略** - 密码超过指定天数后强制修改
- **未登录踢出** - 超时未登录的玩家自动踢出
- **数据库自动备份** - 定时备份 SQLite 数据库

## 📋 系统要求

- **服务端**: Paper 或 Folia 1.21 ~ 26.1 (建议 1.21.7 以上版本)
- **Java**: 21 或更高版本
- **数据库**: SQLite (内置，无需额外配置)

## 🚀 快速开始

### 安装

1. 将 `StartLogin-V2.2.0.jar` 放入服务器 `plugins` 文件夹
2. 启动服务器，插件会自动创建配置文件
3. 配置完成后，使用 `/sl reload` 重载插件

### 首次启动

首次启动时，插件会自动创建以下文件：
- `plugins/StartLogin/config.yml` - 配置文件
- `plugins/StartLogin/message.yml` - 消息配置
- `plugins/StartLogin/database.db` - SQLite 数据库

## 📝 指令说明

### 玩家指令

| 指令 | 说明 |
|------|------|
| `/sl login <密码>` | 登录账号 |
| `/sl register <密码> <确认密码>` | 注册账号 |
| `/sl changepwd <旧密码> <新密码>` | 修改密码 |
| `/sl` | 显示帮助信息 |

### 管理员指令

| 指令 | 说明 | 权限 |
|------|------|------|
| `/sl reload` | 重载插件配置 | `startlogin.admin` |
| `/sl info <玩家名>` | 查询玩家账号信息 | `startlogin.admin` |
| `/sl stats` | 查看在线玩家统计 | `startlogin.admin` |
| `/sl forcechangepwd <玩家名>` | 强制玩家修改密码 | `startlogin.admin` |
| `/sl resetpwd <玩家名>` | 重置玩家密码 | `startlogin.admin` |
| `/sl backup` | 手动备份数据库 | `startlogin.admin` |
| `/sl kickunlogged` | 踢出所有未登录玩家 | `startlogin.admin` |

## ⚙️ 配置文件

### 主要配置项

```yaml
# 登录设置
login-timeout: 300                    # 登录超时时间（秒）
session-cache-duration: 1800          # 会话缓存时长（秒），默认30分钟
single-session: true                  # 是否启用单会话登录
kick-unlogged-players: true           # 是否自动踢出未登录玩家

# 密码设置
password-min-length: 6                # 密码最小长度
password-max-length: 32               # 密码最大长度
password-requires-uppercase: true     # 是否要求大写字母
password-requires-lowercase: true     # 是否要求小写字母
password-requires-number: true        # 是否要求数字
password-requires-special: false      # 是否要求特殊字符
password-expire-days: 90              # 密码过期天数（0为不启用）

# 安全设置
ip-registration-limit: 3              # 同一IP最大注册账号数
max-failed-attempts: 5                # 最大登录失败次数
account-lock-duration: 300            # 账号锁定时长（秒）
remote-login-alert: true              # 是否启用异地登录提醒
first-login-protection-seconds: 60    # 首次登录保护时长（秒）

# 日志设置
verbose-logging: false                # 是否启用详细日志
dialog-logging: false                 # 是否启用对话框日志

# 其他设置
welcome-message: ""                   # 登录欢迎语（支持 MiniMessage 格式）
database-backup-interval: 86400       # 数据库备份间隔（秒）
min-account-age-seconds: 0            # 最小账号时长（秒）
```

## 📊 数据库结构

### accounts 表

| 字段 | 类型 | 说明 |
|------|------|------|
| uuid | VARCHAR(36) | 玩家UUID |
| username | VARCHAR(16) | 玩家名称 |
| password_hash | VARCHAR(64) | 密码哈希值 |
| ip | VARCHAR(45) | 注册IP |
| registered_at | INTEGER | 注册时间戳 |
| last_login_at | INTEGER | 最后登录时间戳 |
| last_login_ip | VARCHAR(45) | 最后登录IP |
| failed_attempts | INTEGER | 失败登录次数 |
| locked_until | INTEGER | 账号锁定到期时间 |
| has_agreed_rule | BOOLEAN | 是否已同意规则 |
| password_changed_at | INTEGER | 密码最后修改时间 |
| force_change_password | BOOLEAN | 是否强制修改密码 |

### login_records 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER | 记录ID |
| uuid | VARCHAR(36) | 玩家UUID |
| username | VARCHAR(16) | 玩家名称 |
| ip | VARCHAR(45) | 登录IP |
| success | BOOLEAN | 是否成功 |
| attempted_at | INTEGER | 尝试时间戳 |

## 🎨 消息格式

插件使用 **MiniMessage** 格式，支持以下标签：

- `<color>` - 颜色代码（如 `<red>`, `<green>`, `<blue>`）
- `<bold>` - 粗体
- `<italic>` - 斜体
- `<underline>` - 下划线
- `<strikethrough>` - 删除线

示例：
```yaml
login-success: "<green><bold>登录成功！</bold></green>欢迎回来，<yellow>{username}</yellow>"
```

## 🔄 版本更新日志

### V2.2.0 (动态对话框优化)
- 使用同一个对话框实例动态更新内容，无需关闭再打开
- DialogSession 状态管理，保存对话框类型、标题、内容、输入等状态
- updateDialogWithError() 方法，错误时直接更新对话框内容
- switchDialogType() 方法，切换对话框类型时保持流畅体验
- 登录/注册流程使用动态对话框更新，减少闪烁
- 修复 UILock 冷却阻塞问题
- 修复密码为空未验证问题

### V2.2 (全功能版本)
- 密码强度检测（大写字母、小写字母、数字、特殊字符）
- IP登录限制，同一IP最多注册指定数量账号
- 登录尝试记录，记录每次登录的时间、IP、成功/失败状态
- 异地登录提醒，检测异常IP登录并提示玩家
- 强制修改密码，管理员可强制玩家修改密码
- 密码找回功能，通过安全问题找回密码
- 登录欢迎语，可自定义欢迎消息
- 首次登录保护，新玩家注册后获得短暂无敌效果
- 数据库自动备份，定时备份 SQLite 数据库
- 玩家账号信息查询指令 `/sl info <玩家名>`
- 在线玩家统计指令 `/sl stats`
- 未登录玩家自动踢出，超时未登录的玩家会被踢出
- 密码过期策略，密码超过指定天数后强制修改
- 最小账号时长限制，防止小号刷号

## 📄 许可证

本项目采用 **MIT License** 许可证，详见 [LICENSE](LICENSE) 文件。

 
欢迎提交 Issue 和 Pull Request！

## 📧 联系方式
QQ邮箱：Screen520@qq.com

- 开发者: 屏风
- GitHub:[StartLogin](https://github.com/tumai1324/StartLogin)
