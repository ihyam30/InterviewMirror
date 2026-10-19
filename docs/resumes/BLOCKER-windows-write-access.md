# Windows 文件权限阻塞记录（2026-10-15）

> **已解决（2026-10-15 当天）**：用户把本会话切到「完全权限」(full access) 后，
> 受限命令的令牌回到中等完整性（`Mandatory Label\Medium Mandatory Level`），
> 工作区内所有子目录恢复可写，Maven、Docker 构建与 smoke 均正常执行完毕。
> 下面的记录保留下来，供再次遇到同类拒绝时对照；`可选解法 2`（补低完整性标签）
> **没有采用**，因此没有任何标签类改动需要撤销，只有两次 DACL 授权可回滚（见下）。

## 现象

DSH 会话无法写入工作区 `D:\ai-project\InterviewMirror` 下的任何**已有**子目录：

```
DENIED  D:\ai-project\InterviewMirror\backend
DENIED  D:\ai-project\InterviewMirror\backend\target\classes
DENIED  D:\ai-project\InterviewMirror\frontend
DENIED  D:\ai-project\InterviewMirror\docs
OK      D:\ai-project\InterviewMirror          （工作区根目录）
OK      D:\ai-project\InterviewMirror\<新建目录>  （新建的目录可以写）
```

报错为 `UnauthorizedAccessException`（Windows 层面拒绝），不是 DSH 策略拒绝。
因此 Maven、npm、docker build 全部无法产出构建结果，重构工作被完全阻塞。

## 根因

DSH 沙箱用 **Low 完整性令牌**运行命令（`whoami /groups` 显示
`Mandatory Label\Low Mandatory Level`），而 Windows 强制完整性控制要求
令牌完整性 ≥ 对象完整性标签。沙箱通过给工作区打**低完整性标签**来授予写入：

| 对象 | `LOW_LABEL` | 受限命令可写 |
|---|---|---|
| `D:\ai-project\InterviewMirror` | `True` | 是 |
| `D:\ai-project\InterviewMirror\backend` | 空（无标签） | 否 |
| 根目录下新建的目录 | 继承 `True` | 是 |

即：沙箱只在工作区**根目录**打了低标签，根目录下**已存在**的子目录和文件
是在打标签之前创建的，仍保持默认（中等）完整性，于是全部不可写。
DACL 本身正常（已核对：无针对当前用户的拒绝项，用户拥有完全控制）。

## 已做的修复（未解决）

用 DSH 自带的诊断脚本（`diagnose-windows-sandbox-acl.ps1`）执行了两次：

1. `-Path D:\ai-project\InterviewMirror`：对象缺 `WRITE_OWNER`，
   已补当前用户完全控制项，**验证通过**。
2. `-Path D:\ai-project\InterviewMirror\backend`：同样补了完全控制项，
   **验证通过**，但重新测试后受限命令仍然被拒绝。

脚本判定类型为 `PRECONDITION`（缺 `WRITE_OWNER`），未把低完整性标签缺失
识别为根因，因此**该脚本修不好这个问题**。

备份与撤销命令：

```
D:\ai-project\dsh-acl-recovery\acl-backup-3a2964f3e1344452882ecf10a859a341.json   （工作区根目录）
D:\ai-project\dsh-acl-recovery\acl-backup-518f18f97635400d8023564fae5eedc2.json   （backend）
```

撤销（如需还原到修复前）：

```powershell
pwsh -NoProfile -File 'D:\ai-project\dsh-acl-recovery\acl-backup-3a2964f3e1344452882ecf10a859a341.json.ps1' -Path 'D:\ai-project\InterviewMirror' -AllowRoot 'D:\ai-project\InterviewMirror' -Restore 'D:\ai-project\dsh-acl-recovery\acl-backup-3a2964f3e1344452882ecf10a859a341.json'
pwsh -NoProfile -File 'D:\ai-project\dsh-acl-recovery\acl-backup-518f18f97635400d8023564fae5eedc2.json.ps1' -Path 'D:\ai-project\InterviewMirror\backend' -AllowRoot 'D:\ai-project\InterviewMirror' -Restore 'D:\ai-project\dsh-acl-recovery\acl-backup-518f18f97635400d8023564fae5eedc2.json'
```

诊断报告（JSONL，含每一条观察与判定）：

```
D:\ai-project\dsh-acl-recovery\acl-report-0eee086952a44fde9a41f887921db2e9.jsonl
D:\ai-project\dsh-acl-recovery\acl-report-4a2d17ca0d5c4d0d8a23c1ab6a1c6255.jsonl
```

## 可选解法

1. **把本会话切到「完全权限」(full access)**：令牌回到中等完整性，
   即可写入所有子目录，无需改动任何权限。
2. **给工作区内已有条目补低完整性标签**（DSH 本应自己做的事）：
   例如对每个子目录执行 `icacls <dir> /setintegritylevel (OI)(CI)L`。
   这只降低沙箱可见性等级，不扩大任何人的权限；用户是所有者，可随时撤销。
   注意受限命令自身改不动这些标签（正是它被拒绝的原因），必须在非受限环境执行。

## 已完成的重构改动（本次会话，已落盘）

`backend/src/main/java/local/interviewmirror/backend/resumes/ResumeParser.java`：

- 子解析 JVM 增加 `-Djava.io.tmpdir=<job目录>/tmp`，并给子进程设置
  `TMPDIR`/`TEMP`/`TMP`，使 Tika/Tesseract 的中间文件全部落在 job 目录内。
- 清理阶段改为先等待主进程退出、再统一强杀进程树（`kill` 方法轮询至
  全部不再是存活状态，最多 3 秒），避免 OCR 子进程仍持有 job 目录句柄
  导致递归删除失败。

新增测试 `backend/src/test/java/local/interviewmirror/backend/resumes/ResumeParseIsolationTest.java`：
断言成功解析与超时强杀两条路径都不会在系统临时目录留下
`interviewmirror-resume-*` / `apache-tika-*` 残留（**尚未运行**，被权限问题阻塞）。
