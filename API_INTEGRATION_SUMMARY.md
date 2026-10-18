# 课悟 Kewu · 后端 API 接入汇总

> 目标：把已有的后端接口全部接入软件，缺界面的补齐界面，全部使用 **coui 液态玻璃** 风格。
> 状态：**`assembleDebug --offline` 编译通过（BUILD SUCCESSFUL）**。本文档为接入清单与现状汇总。

---

## 一、接入总览

| 模块 | 接口数 | 已接入界面 | 仅基础设施/旧版（无界面） |
|------|-------|-----------|------------------------|
| 认证/用户 | 3 | `login` `register` `me` | — |
| 课程 | 3 | `getCourses` `getCourse` | `bindCourse` |
| 课堂笔记 | 4 | `getNotes` `getNote` | `postNote` `getNoteCache` |
| 作业 | 13 | `getAssignments` `getAssignment` `getAssignmentStats` `getMySubmissions` `submitAssignment` `aiPrereview` `getSimilarity` `getReviewQueue` `getAnalytics` `reviewSubmission` `publishAssignment` | `getSubmissions` `getSubmission` |
| 签到 | 11 | `getAttendances` `getAttendance` `getAttendanceCode` `attendanceCheckIn` `getMyAttendance` `getAttendanceRecords` `getAttendanceStats` `getAttendanceRisk` `getAttendanceRealtime` `closeAttendance` | `createAttendance` |
| 录音/会话 | 5 | — | `uploadRecording` `fuseRecordings` `createSession` `getSession` `obsHealth` `health` |
| 旧版点名 | 2 | — | `checkin` `getCheckins` |

**结论**：业务侧接口（鉴权、课程、笔记、作业、签到）已全部有对应界面消费；剩余未接 UI 的均为纯基础设施/健康检查/旧版点名体系（已被 `attendanceCheckIn` 体系取代）。

---

## 二、界面 <-> 接口 接线矩阵

### 1. 鉴权 / 用户
- `login` / `register` → **LoginScreen**（已存在）
- `me` → **ClassroomPage** / **MyPage**：进入时刷新昵称、学号等用户信息

### 2. 课程
- `getCourses` → **ClassroomPage**（今日课程/状态区）、**TaPage**（老师课程列表）、**MyPage**
- `getCourse(courseId)` → **NotesHub.CourseDetailScreen**（课程详情头部）
- `bindCourse` → **TeacherTools.BindCourseScreen**（输入课程 ID 绑定，列出已绑定课程）

### 3. 课堂笔记
- `getNotes(courseId)` → **NotesHub.CourseDetailScreen**（笔记列表）
- `getNote(courseId, noteId)` → **NotesHub**（点击笔记行懒加载展开正文）
- `postNote` / `getNoteCache` → **TeacherTools.PostNoteScreen**（选课程 + 编辑内容发布，选课后自动预填已有草稿）

### 4. 作业中心
全部接入 **AssignmentHub.AssignmentDetailScreen**：
- `getAssignment(id)` 元信息
- `getMySubmissions(id, studentId)` 我的提交
- `getAssignmentStats(id)` 统计（均分/分布/版本/高风险）
- `aiPrereview(submissionId)` AI 预批阅建议
- `getSimilarity(id)` 抄袭相似度
- `getReviewQueue(id)` 待批队列
- `getAnalytics(courseId)` 学情分析
- `submitAssignment(...)` 学生提交
- `reviewSubmission(...)` 老师批阅
- `publishAssignment(...)` 老师发布作业
- `getAssignments(courseId?)` → **TaskPage**（待交作业）、**TaPage**、**NotesHub**（课程内作业）
- `getSubmissions` / `getSubmission` → ⚠️ 仅 API 存在，单份提交明细界面未做

### 5. 签到
- `getAttendances(courseId)` → **ClassroomPage**「进行中签到」入口卡（取第一门课的 OPEN 会话）
- **入口身份分流**（后端无角色字段，`session.publisher == 当前用户昵称` 判定教师）：教师点卡 → **AttendanceHub** 详情（**二维码展示仅教师可见**：`QrCodeView` 渲染 `qrContent`，30s 动态刷新）；学生点卡 → **ScanPage** 扫码
- 学生签到两路径（2026-10-18 晚定版）：
  1. **扫码**（`ui/ScanPage.kt` + `QrScanner.android.kt`，CameraX+ML Kit）：扫 `kewusign://attend?sid&code` → `attendanceCheckIn` → 成功返回首页；失败带暗号跳暗号页继续
  2. **暗号**（`ui/CodeCheckInPage.kt`，独立页）：暗号 + **随堂问题答案**（QUIZ 模式必答，否则后端拒「请回答随堂问题」）→ `attendanceCheckIn(code, answer)` 一次提交；已真机端到端验证通过（刘宇豪 20260551073 签到成功入库）
- **AttendanceHub.AttendanceDetailScreen** 消费：`getAttendance`（**注意：该接口现为 `{session,stats}` 嵌套，KewuApi 已兼容两形状**）`getAttendanceCode` `attendanceCheckIn` `getMyAttendance` `getAttendanceRecords` `getAttendanceStats` `getAttendanceRisk` `getAttendanceRealtime` `closeAttendance`；学生视图只保留头部信息 + 我的签到/签到动作
- `createAttendance` → **TeacherTools.CreateAttendanceScreen**（教师发起签到：选课程 + 标题 + 有效时长）

### 6. 录音 / 会话 / 健康检查
- `uploadRecording` → **ClassroomPage.RecordingOverlay** 已接入**真实录音 + 上传**：⏺ 录音（`AudioRecorder` + `RECORD_AUDIO` 权限）→ ⏹ 停止生成 WAV → 自动 multipart 上传，失败显示重试
- `fuseRecordings` `createSession` `getSession`：录音融合 / 会话管理，仍无界面（基础设施）
- `health` `obsHealth`：服务健康检查，无界面
- `checkin` `getCheckins`：旧版点名接口，已被 `attendanceCheckIn` 体系取代，新 UI 不调用

---

## 三、本轮补全的界面（之前缺失，本次新建）

| 界面 | 文件 | 说明 |
|------|------|------|
| 作业中心·详情 Hub | `ui/AssignmentHub.kt` → `AssignmentDetailScreen` | 作业元信息 + 我的提交 + 统计 + AI 预批阅 + 相似度 + 待批队列 + 学情 + 提交 + 老师发布/批阅 |
| 课程详情 Hub | `ui/NotesHub.kt` → `CourseDetailScreen` | 课程信息 + 笔记列表（懒加载）+ 课程内作业，点击作业下钻到 AssignmentHub |
| 签到详情 Hub | `ui/AttendanceHub.kt` → `AttendanceDetailScreen` | 签到信息 + 我的签到/动态码 + 统计 + 实时 + 风险 + 记录 + 老师结束 |

下钻接线方式（统一模式）：在各 tab 根节点用 `BackHandler` + 状态变量（`drill` / `assignmentId` / `courseId`）做页内切换，不嵌套导航；课堂页 `ClassroomPage` 用 `ClassroomDrill` 三态（`NONE` / `TIMETABLE` / `ATTENDANCE`）切换首页 / 课表 / 签到详情。

已存在并接线的界面：`LoginScreen` `ClassroomPage` `TaPage` `TaskPage` `MyPage` `TimetablePage` `MainScreen`。

---

## 四、关键设计约束（避免踩坑）

1. **coui 液态玻璃硬约束**：`KewuGlassCard` 的采样层必须位于 `Box().layerBackdrop(pageBackdrop)` 背景层**之外**（互为兄弟），且绝不能用 `alpha()` / `graphicsLayer()` 包裹采样者，否则首帧 RenderThread **SIGSEGV**。所有 Hub 页均遵循：背景层 `layerBackdrop` + `background(M3 colorScheme)`，内容卡用 `KewuGlassCard(backdrop = pageBackdrop)`。
2. **无序列化插件**：项目未启用 `kotlinx.serialization` 编译器插件，全部用 `Json.parseToJsonElement` 手解；Ktor 4xx/5xx 抛 `ResponseException`，需 `try/catch` 读取 `e.response`。
3. **后端 8080 当前挂了**（队友部署，需队友重启）：所有拉取接口已做「真实拉取 + 失败回退 mock」兜底，断网/服务挂时不白屏、不崩。
4. **二维码已自绘渲染**：`ui/QrCode.kt` 内置纯 Kotlin 二维码编码器（移植 Nayuki QR-Code-generator，Byte 模式/纠错 M/固定 mask 0），`QrCodeView` 用 Canvas 画出可扫二维码（含 4 模块静默区）；签到页 `DynamicCodeCard` 取 `qrContent`（缺省退回 `code`）生成，白底保证对比度。已用 OpenCV 离线解码验证可扫（解出 `kewusign://attend?sid=...&code=...`）。
5. **`weight` 是 `RowScope`/`ColumnScope` 作用域扩展**：只能在 `Row`/`Column` 内容 lambda 内使用，切勿 `import androidx.compose.foundation.layout.weight`（会误解析到包内 `RowColumnParentData.weight` 内部属性）。
6. **CameraX 扫码（2026-10-18 新增依赖）**：`CameraSelector.DEFAULT_BACK_CAMERA`（无 `DEFAULT_BACK`）；`Preview.setSurfaceProvider(...)` 写显式调用；相机权限先装新 APK 再 `pm grant`；ML Kit 在 Google Maven 不在 Maven Central；gradle.properties 的 7890 死代理已移除。

---

## 五、仍待补齐的界面（如需完整闭环）

本轮已补全：老师「发起签到」(`createAttendance`)、「绑定课程」(`bindCourse`)、「发布课堂笔记」(`postNote`)，以及首页**真实录音上传**(`uploadRecording`)。全部在新文件 `ui/TeacherTools.kt`（从「我的」tab 进入），录音按钮在首页（课堂页）。

仍待补齐（如需完整闭环）：
- 单份作业提交明细 (`getSubmission`/`getSubmissions`) 界面
- 录音融合 (`fuseRecordings`)、录音会话管理 (`createSession`/`getSession`) 界面
- 服务健康检查 (`health`/`obsHealth`) 可视化界面

> 说明：项目根目录另有若干自动生成的报告（`API_IMPLEMENTATION_PLAN.md`、`COMPLETE_*.md`、`FINAL_*.md`、`PROGRESS_REPORT.md`、`TODO_LIST.md`、`test_report.md`），内容与本文档重复，可清理保留本汇总即可。
