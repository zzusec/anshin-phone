# 远程清理协助中转服务

Node.js 22 原生 HTTP 服务，零运行时依赖，无需 `npm install`。它只暂存经手机明确批准的卸载请求和手机回报的结果，**不连接设备、不提供 ADB、不运行 shell、不执行卸载**。手机仍是最终执行和安全确认边界。

所有会话仅保存在当前进程内存中，默认 30 分钟；重启、到期或手机撤销后，所有相关凭证和任务失效。不写清理记录，不持久化 token 或清单，不自动部署公网。

## 启动与测试

在 `android-cleanup` 根目录执行：

```sh
node server/src/index.mjs
node --test server/test/*.test.mjs
```

默认监听 `127.0.0.1:8787`，`GET /api/health` 返回 `{"ok":true}`。默认邀请基址为 `http://127.0.0.1:8787`。此默认值只适合本机调试，另一台手机不能用自身的 `127.0.0.1` 访问电脑服务。

入口读取以下环境变量：

| 变量 | 默认值 | 约定 |
| --- | --- | --- |
| `HOST` | `127.0.0.1` | 本机或反代后的私有监听地址 |
| `PORT` | `8787` | HTTP 端口；`0` 可用于 CLI 启动检查 |
| `PUBLIC_BASE_URL` | `http://127.0.0.1:8787` | HTTPS 公共 origin，或 localhost/loopback HTTP 调试 origin |
| `ALLOW_INSECURE_DEV` | 未开启 | 仅字符串 `1` 显式允许非 loopback 的不安全开发监听，并输出 warning |

更改端口时请同步设置 `PUBLIC_BASE_URL`。基址可带一个末尾 `/`，不能包含子路径、用户凭证、查询参数或 fragment；公网 HTTP 基址始终被拒绝。`HOST=0.0.0.0` 等非 loopback 监听且基址不是 HTTPS 时，默认启动失败。`ALLOW_INSECURE_DEV=1` 不是生产部署方案，也不能放宽公网 HTTP 基址校验。

服务从 `web/dist` 读取构建产物，`/` 对应 `web/dist/index.html`，`/assist/` 对应 `web/dist/assist/index.html`；`/assist` 重定向到 `/assist/`。不会创建、构建或改写网页。未生成静态文件时，相应请求返回可读的 `FILE_NOT_FOUND`，API 仍可运行。没有任意路径 SPA fallback。

## 可测试接口

```js
import { startServer } from './server/src/index.mjs';
import { SessionStore } from './server/src/store.mjs';

const store = new SessionStore();
const server = await startServer({
  host: '127.0.0.1',
  port: 0,
  store,
  publicBaseUrl: 'http://localhost:8787',
});
const address = server.address(); // { address, family, port: 实际随机端口 }
// 测试通过 address.port 访问 API；邀请基址始终使用传入的 publicBaseUrl，不推导随机端口。
await new Promise((resolve, reject) => {
  server.close((error) => error ? reject(error) : resolve());
});
```

`startServer()` 是 async 函数，成功后返回**原生 `http.Server`**，不是 `{server,address}` 包装对象。启动失败会 reject。可传入 `staticDir`、`logger`（`warn/error`）、`allowInsecureDev: true`、`maxBodyBytes`、`cleanupIntervalMs`、`rateLimits` 和 `now`，方便本机测试；没有新增外部依赖或环境变量配置矩阵。

`SessionStore` 可独立调用并测试：`createSession(payload, publicBaseUrl)`、`claim(payload)`、`getDevice(id, deviceToken)`、`approve(id, deviceToken, payload)`、`revoke(id, deviceToken)`、`updateInventory(id, deviceToken, payload)`、`getGuest(id, guestToken)`、`createCommand(id, guestToken, payload)`、`reportResult(id, deviceToken, commandId, payload)`、`cleanup()`。`size` 会先清理到期会话。抛出的 `ServiceError` 包含 `status/code/message`。直接使用状态机时，传入的邀请基址应先通过入口导出的 `normalizePublicBaseUrl()` 校验。

`SessionStore` 的构造选项与默认上限：

```js
new SessionStore({
  now: Date.now,
  sessionTtlMs: 30 * 60 * 1000,
  maxSessions: 100,
  maxInventoryItems: 1000,
  maxPackagesPerCommand: 50,
  maxCommandsPerSession: 100,
});
```

## HTTP 契约

所有带凭证的接口只接受 `Authorization: Bearer <token>`，不接受 query/cookie 中的 token。JSON 请求需使用 `Content-Type: application/json`（可附 `charset=utf-8`）。未知字段、错误类型和重复包名都会被拒绝。

错误统一为：

```json
{"error":{"code":"APPROVAL_REQUIRED","message":"请等待老人手机明确批准后再查看应用或提交清理任务。"}}
```

| 方法与路径 | 凭证 | 请求与成功响应 |
| --- | --- | --- |
| `GET /api/health` | 无 | `200 {ok:true}` |
| `POST /api/sessions` | 无 | `{deviceName,inventory}` → `201 {sessionId,deviceToken,inviteToken,inviteUrl,expiresAt}` |
| `POST /api/claim` | 无 | `{inviteToken,childName}` → `200 {sessionId,guestToken,phase:'claimed',expiresAt}` |
| `GET /api/sessions/:id/device` | device | `200 {phase,childName,commands,expiresAt}`；成功请求刷新设备在线时间 |
| `POST /api/sessions/:id/approve` | device | `{approve:true}` → `200 {phase:'approved'}`；必须先被领取 |
| `DELETE /api/sessions/:id` | device | `200 {phase:'revoked'}`；立即删除会话 |
| `POST /api/sessions/:id/inventory` | device | `{inventory}` → `200 {phase}`；有效更新刷新在线时间 |
| `GET /api/sessions/:id/guest` | guest | `200 {phase,expiresAt,deviceOnline,lastDeviceSeen}`；仅 `approved` 时附 `inventory,commands` |
| `POST /api/sessions/:id/commands` | guest | `{action:'uninstall',packages,acknowledgeDataLoss:true}` → `201 command` |
| `POST /api/sessions/:id/commands/:commandId/result` | device | `{status,results}` → `200 command` |

`inventory` 每项：

```json
{
  "packageName": "org.example.notes",
  "label": "便签",
  "category": "user",
  "removable": true,
  "system": false,
  "versionName": "1.0"
}
```

`versionName` 可省略；其余字段必填。分类仅 `core|optional|user|unknown`。包名需是合法的点分包名（单独的 `android` 也能出现在清单中，但永远不能卸载）。空清单合法；重复包名非法。

初始 phase 为 `created`，领取后为 `claimed`，手机明确批准后为 `approved`。领取只会生成 guest 凭证，不会给出应用清单或执行授权；并发领取也只能一个成功，再次领取返回 `409 INVITE_ALREADY_CLAIMED`。`expiresAt` 是 UTC ISO 时间，任何请求都不会续期。到期、撤销和不存在的会话统一返回 `404 SESSION_NOT_FOUND`。

邀请链接严格为 `PUBLIC_BASE_URL + '/assist/#invite=' + inviteToken`；邀请 token 放在 fragment 中，而不是服务器请求路径中。邀请 token 不能替代任何角色凭证。凭证使用独立的 32 字节加密随机数，以 base64url 编码；状态机只保存 SHA-256 hash，以恒定长度的 `timingSafeEqual` 检查凭证。

### 设备在线与前台轮询

- 仅成功鉴权的 `GET /device` 和有效 `POST /inventory` 更新 `lastDeviceSeen`。创建、批准、guest 轮询、结果回报和非法请求均不刷新它。
- guest 响应无论批准前后，都含 `deviceOnline` 和 `lastDeviceSeen`。首次设备轮询/清单更新前，分别为 `false`、`null`；之后 `lastDeviceSeen` 是 UTC ISO 时间。
- 距离上次设备活动**严格小于 30 秒**时在线；满 30 秒即离线。离线时新任务返回 `409 DEVICE_OFFLINE`，不会入队。已有任务历史仍可查看，既有结果仍可回报。
- 手机首版只需在应用前台协助页面定期轮询（例如每 5 秒），无需增加后台或隐蔽服务。手机和网页应明确提示：**“请保持手机助手打开，并停留在协助页面；退出或切到后台会导致协助离线。”** 网页应根据在线字段禁用操作，不仅根据 `phase:'approved'` 判断。
- 在线字段是最近活动指标，不保证任务执行瞬间手机仍在线；设备退出后最多有 30 秒观察延迟。手机执行前仍必须验证会话、任务、清单与本地权限，不能把排队当作完成。

### 卸载任务与结果

`command` 新建时包含：

```json
{
  "id": "任务 UUID",
  "action": "uninstall",
  "packages": ["org.example.notes"],
  "acknowledgeDataLoss": true,
  "status": "pending",
  "createdAt": "UTC ISO 时间"
}
```

相对于基本 command 契约，额外保留 `acknowledgeDataLoss: true`，确保 device 轮询仍能读取完整请求。更新后附 `results`、`updatedAt`，不会丢掉 `action/packages/createdAt/acknowledgeDataLoss`。device 只收到非终态任务，guest 收到全部历史。请求内重复包名及与未完成任务重叠的应用分别返回 `DUPLICATE_PACKAGE`、`PACKAGE_BUSY`。

整项任务必须只包含当前清单中的 `optional|user`、`removable:true` 应用；`core`、`unknown`、不在清单内或明确不可移除的应用全部拒绝。是否系统应用不能单独决定能否卸载；已知非核心可选系统应用允许移除。服务额外拒绝 Android 核心、系统界面、设置、电话、权限控制、安装器、关键 provider 等关键包，即使清单错误标注为可移除。拒绝名单位于 `store.mjs`，属于保守的额外防护，不能穷尽所有 OEM 关键包；手机必须准确分类并保留自己的关键包校验。

当清单更新使一个仍为 `pending` 的任务不再安全，该任务整体转为 `rejected`，guest 历史可见拒绝原因，device 不再收到它。已经 `running/needs-confirmation` 的任务不会被清单刷新覆盖，以免抹掉设备实际进度；手机仍应在执行每个应用前重新检查安全条件。

任务级回报状态可为 `running|needs-confirmation|succeeded|partial|failed|rejected`。`results` 是数组，每项必须包含：

```json
{"packageName":"org.example.notes","status":"succeeded","message":"已卸载"}
```

**单应用 status 仅 `succeeded|needs-confirmation|failed|rejected`**，不能使用 `running/pending/partial`。`message` 必须是字符串，可以为空，最多 2000 字符。任务级 `running` 可先回报 `results: []`，然后逐步提供单应用结果。

校验规则：

- 结果只能包含本任务的包名，每次上报不能重复。非终态报告可提交子集；服务合并保留先前结果，不允许已成功/失败/拒绝的应用结果退回其他状态。
- `succeeded` 必须在**本次请求**提交所有包，且每项都成功。
- `partial` 必须在**本次请求**提交所有包，均为单应用终态，且同时含成功及失败/拒绝。
- `failed/rejected` 不能隐藏已成功结果或未完成的已上报应用；`rejected` 中已给出的结果只能是 `rejected`。尚未产生逐包结果的整项失败/拒绝可以提交空数组，但不能借此伪造成功。
- `succeeded|partial|failed|rejected` 都是不可改写的任务终态。再次回报返回 `409 COMMAND_TERMINAL`。

服务校验的是回报一致性，不能独立证明实际卸载成功。手机应仅在本地执行并验证完成后报告 `succeeded`，系统仍需确认时报告 `needs-confirmation`，不能以中转成功、请求提交或弹出确认框冒充卸载完成。

## 安全与资源限制

- API 和静态响应均 `Cache-Control: no-store`，设置 `Referrer-Policy: no-referrer`、`nosniff`、禁止嵌入页面的 frame 策略及基础 Permissions Policy。
- CSP 只允许同源资源；禁止 inline script、eval、object、base、form 提交和第三方来源。网页必须使用同源外置 JS/CSS，不依赖 CDN 或内联启动脚本。
- 不发送任何 CORS 开放头；API 显式拒绝与 `PUBLIC_BASE_URL` 不一致的 `Origin`。同源网页调用可行，原生手机不需发送 Origin。
- 静态路径先解码再校验，拒绝 dot-segment、反斜线、控制字符和错误编码。拒绝 dotfile，以及 `realpath` 后逃逸构建目录的软链接。
- JSON 最大 256 KiB，要求有效 UTF-8，不接收压缩请求。限制请求头、HTTP 请求/头超时和每连接请求数量。
- 默认按真实连接 IP 限制创建 10 次/分钟、领取 20 次/分钟、鉴权失败 20 次/分钟；创建和领取失败请求也计数。失败达到上限后，该 IP 鉴权路由在窗口结束前返回 `429 RATE_LIMITED`；正常轮询不消耗失败额度。429 附 `Retry-After`。
- 限流桶最多 10,000 个；默认每分钟清理到期会话和限流桶，请求访问到期会话时也立即失效。活动会话/清单/每任务包数/每会话任务历史均有上限。
- 不信任 `X-Forwarded-For`，避免伪造 IP 绕过限流。因此单一反代后的流量会共用应用层 IP 限流额度；生产必须在可信反代边界补充分客户端 IP 的限流，并按实际规模通过 `startServer({rateLimits:{...}})` 调整服务限额，而不是直接信任任意转发头。
- 日志不记录请求 URL、token、Authorization、清单、请求/响应体或原始错误消息。预期错误只返回可读错误码；非预期错误及清理失败记录经过限制的错误代码，并返回真实失败，不静默伪成功。

常见错误码还有 `INVALID_JSON`、`INVALID_BODY`、`JSON_REQUIRED`、`BODY_TOO_LARGE`、`INVALID_TOKEN`、`INVALID_INVITE`、`APPROVAL_REQUIRED`、`DEVICE_OFFLINE`、`DATA_LOSS_ACK_REQUIRED`、`PACKAGE_NOT_ALLOWED`、`INCONSISTENT_RESULTS`、`RESULT_REGRESSION`、`SESSION_LIMIT`、`COMMAND_LIMIT` 和 `INTERNAL_ERROR`。客户端应展示 `error.message`，不要仅根据 HTTP 成功连接或非空响应判断业务成功。

## 生产 HTTPS 反代（仅说明，不自动部署）

本服务本身仅 HTTP；将它保留在 loopback，由已配置可信证书的反代终止 TLS，并在同一个 HTTPS origin 服务网页和 API。例如：

```sh
HOST=127.0.0.1 PORT=8787 PUBLIC_BASE_URL=https://assist.example \
  node server/src/index.mjs
```

Nginx 反代示意（证书路径、域名、反代层限流与运维配置需自行确定）：

```nginx
server {
    listen 443 ssl;
    server_name assist.example;
    ssl_certificate     /etc/ssl/assist/fullchain.pem;
    ssl_certificate_key /etc/ssl/assist/privkey.pem;

    access_log off;
    location / {
        proxy_pass http://127.0.0.1:8787;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Connection "";
        proxy_cache off;
        proxy_buffering off;
    }
}
```

反代应保留服务的 CSP、Referrer-Policy、no-store 等响应头，不注入第三方分析脚本，不记录 Authorization 或 JSON body，也不要增加 wildcard CORS。HTTPS 基址时服务附加 HSTS；仅配置 `PUBLIC_BASE_URL=https://...` **不会加密实际 HTTP 连接**，必须先有真实 TLS 反代并通过防火墙阻止直接访问后端。反代自身拒绝的请求也应提供合适的用户可读错误。

只运行单个内存服务进程：多个独立实例不共享会话，也不要做无粘性的负载均衡。更换进程时客户端应明确提示会话失效并重新邀请；不要为了保留会话把凭证或清单写到日志/磁盘。生产反代和真实手机/网页执行行为不在本目录测试覆盖内。
