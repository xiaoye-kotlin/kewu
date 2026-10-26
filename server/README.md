# kewu 后端（:server 模块）

用 Ktor + Kotlin 写，跟前端（composeApp）共用同一个 Gradle 工程，但彼此独立。

## 跑起来
```bash
./gradlew :server:run
```
默认 8080 端口，浏览器开 http://localhost:8080/health 看是否起来了。

## 录音相关接口（本期重点）
- `POST /api/recordings/upload`
  - multipart 上传：表单字段 `audio`（音频文件）+ `classId` / `sessionId` / `deviceId` / `sampleRate` / `durationSec`
  - 返回：录音 id、存储地址（本地或 OBS）、质量打分、时间戳
- `POST /api/recordings/fuse`
  - JSON 传入同一节课多端录音，后端择优 + 标记多源，返回融合结果

## 课堂缓存接口（Redis）
- `POST /api/notes` `GET /api/notes/{classId}` —— 课堂纪要
- `POST /api/checkin` `GET /api/checkin/{classId}` —— 签到状态
- `POST /api/sessions` `GET /api/sessions/{id}` —— 协同会话

## 配置（application.conf）
- 存储：`app.storage.mode = local`（开发）或 `obs`（生产，ak/sk 走环境变量 `OBS_AK` / `OBS_SK`）
- Redis：`app.redis.*`，本地没起 Redis 会自动降级，接口不会挂

## 说明
AI 模块（总结/问答）先留了接口没接，等能力就绪替换 `NoOpAiService` 即可。

## 本地实测结果（2026-10-15，local 模式 + 无 Redis）
| 接口 | 结果 |
| --- | --- |
| `GET /health` | 200，返回 status/time |
| `POST /api/recordings/upload` | 201，返回 recordingId/url/score，文件落盘到 `server/uploads/` |
| 上传缺 `classId` | 400，`{"error":"缺少 audio 文件或 classId"}` |
| 质量打分 | 44.1kHz 录音 0.8 分，16kHz 录音 0.65 分，能区分音质 |
| `POST /api/recordings/fuse` | 200，择优选高分段作 primary，其余进 mergedSources |
| 纪要/签到/会话 读写 | 正常；本地没起 Redis 时读接口降级（空数据/404），不会 500 |

## 部署（华为云一键）
仓库根目录已备好：
- `Dockerfile`：两阶段构建，产出 `kewu-server` 镜像（`gradle :server:installDist` → JRE 运行时）
- `codearts-pipeline.yml`：CodeArts 流水线 YAML 骨架（构建 → 推 SWR → 部署），把两个 `jobId` 占位符换成自己账号下的任务 ID 即可用
- 生产环境把 `application.conf` 里 `app.storage.mode` 改成 `obs`，并配上 `OBS_AK` / `OBS_SK` 环境变量

> **2026-10-26 起本模块已并入 `kewu-8080/`（包 `com.kewu.recserver`），线上只跑 8080 一个端口。** 本目录仅作历史参考，改录音 / AI / 课堂缓存接口请改 `kewu-8080/src/main/kotlin/com/kewu/recserver/`。
