# 面试 SSE 协议

Endpoint：`GET /api/v1/interviews/{id}/events`，需登录且只能连接自己的 interview。浏览器 `EventSource` 自动管理重连；服务端接受 `Last-Event-ID` 并从 PostgreSQL 事件表继续回放。

SSE event 名称：

- `interview.created`
- `interview.started`
- `interview.question`
- `interview.answer.saved`
- `interview.completed`
- `interview.error`

每条消息的 SSE `id` 是数据库递增 `event_id`。data envelope：

```json
{
  "schemaVersion": "interviewmirror.sse-event.v1.0.0",
  "eventId": 17,
  "interviewId": "uuid",
  "type": "interview.question",
  "timestamp": "2026-10-04T08:00:00Z",
  "payload": { "turnId": "uuid", "question": "..." }
}
```

客户端把事件作为刷新提示，然后调用 REST `GET /api/v1/interviews/{id}` 与 `/turns`；SSE 不替代持久化事实。
