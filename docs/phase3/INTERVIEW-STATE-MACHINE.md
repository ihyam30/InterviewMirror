# 面试状态机与恢复

## 业务状态

| 状态 | 含义 | 允许动作 |
|---|---|---|
| `CREATED` | 配置、来源快照及授权已保存 | start |
| `PREPARING` | start 正在准备问题计划或首题 | 等待或过期恢复 |
| `START_FAILED` | 准备失败，配置和来源快照保留 | start retry |
| `RUNNING` | 当前存在可回答 turn 或回答转移正在处理 | answer、replace（仅未回答 MAIN）、end、REST/SSE resume |
| `COMPLETING` | 为 finalize 预留的业务状态；当前同步 finalize 在事务内完成 | 内部 finalize |
| `COMPLETE` | 正常完成或用户提前结束 | 只读 |

图节点和图内状态保存在 LangGraph checkpoint，不映射成业务状态：

```text
START → prepare → select_main → ask → wait_for_answer
  ├─ ANSWER → evaluate_answer ─┬─ follow_up → ask → wait_for_answer
  │                            └─ next_main → select_main / complete
  ├─ REPLACE → replace_question → ask → wait_for_answer
  └─ END → complete
```

## 持久化顺序

1. `POST answers` 在短数据库事务中锁 session、验证 owner/status/active turn、按 `clientRequestId` 幂等保存回答、记录 `PENDING` transition 和持久化事件，然后提交。
2. 调度器用条件更新将 transition `PENDING → PROCESSING`，只有一个 worker 能领取。
3. worker 用 `GraphInput.resume()` 推进固定 `threadId=interviewId` 的 PostgreSQL checkpoint。
4. worker 在新事务保存下一 turn 或完成状态，并记录持久化 SSE 事件。
5. 超过 120 秒的旧 `PROCESSING` transition 回收为 `PENDING`；图快照中的 `lastAnsweredTurnId` 防止重复消费回答。

数据库 turn 表是 transcript 真相来源；checkpoint 只恢复图控制状态。SSE 使用数据库 event id，可通过 `Last-Event-ID` 补发；页面始终以 REST snapshot 校准。

## 并发边界

- Answer：`SELECT … FOR UPDATE` + active turn/status 条件更新 + unique `(interview_id, answer_request_id)`。
- Create：unique `(owner_id, client_request_id)` 并比较 request fingerprint。
- Worker：transition 条件更新确保单活跃消费者。
- Confirmed source：DocumentService 先按 owner/type 读取并验证 `CONFIRMED`，再创建来源快照。
- 资料被删除或编辑后，已创建场次继续使用快照，新场次不能使用已删除/未确认资料。

## 故障恢复

- 模型追问调用失败：保留回答、按有限安全 fallback 跳到下一主问题并记录错误事件。
- 首题准备失败：状态回到 `START_FAILED`，用户可安全重试；已有问题计划/checkpoint 可重用。
- 应用中断：DB 状态和 `PENDING` transition 保留；启动后的 recovery worker 重新领取。
- SSE 断开：浏览器自动重连并带 Last-Event-ID；新页面读取 REST transcript 后订阅。

H2 API/业务恢复逻辑已通过自动化测试；完整 Spring Boot + PostgreSQL 业务状态和 graph checkpoint 的双 JVM 重启恢复 smoke 也已通过。具体命令和隔离方式见 [EVALUATION.md](EVALUATION.md)。
