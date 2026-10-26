package com.kewu.recserver.routes

import com.kewu.recserver.RecServicesKey
import com.kewu.recserver.model.CheckInRecord
import com.kewu.recserver.model.CheckInView
import com.kewu.recserver.model.ClassNote
import com.kewu.recserver.model.ClassroomSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant

// 课堂缓存相关：纪要、签到、会话三块，全走 Redis
fun Route.classroomRoutes() {
    val services = application.attributes[RecServicesKey]

    post("/api/notes") {
        val note = call.receive<ClassNote>()
        val stamped = if (note.updatedAt.isBlank()) note.copy(updatedAt = Instant.now().toString()) else note
        // Redis 真写进去了才说 ok；连不上就如实报降级，别假成功让前端以为存好了
        if (services.redis.cacheClassNotes(stamped.classId, stamped.content)) {
            call.respond(mapOf("ok" to true))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false, "degraded" to true, "error" to "redis unavailable, note not persisted"))
        }
    }

    get("/api/notes/{classId}") {
        val classId = call.parameters["classId"]!!
        val content = services.redis.getClassNotes(classId)
        if (content == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "no notes"))
        else call.respond(mapOf("classId" to classId, "content" to content))
    }

    post("/api/checkin") {
        val r = call.receive<CheckInRecord>()
        val stamped = if (r.at.isBlank()) r.copy(at = Instant.now().toString()) else r
        if (services.redis.setCheckIn(stamped.classId, stamped.studentId, stamped.status)) {
            call.respond(mapOf("ok" to true))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false, "degraded" to true, "error" to "redis unavailable, check-in not persisted"))
        }
    }

    get("/api/checkin/{classId}") {
        val classId = call.parameters["classId"]!!
        call.respond(CheckInView(classId, services.redis.getCheckIns(classId)))
    }

    post("/api/sessions") {
        val s = call.receive<ClassroomSession>()
        val stamped = if (s.startedAt.isBlank()) s.copy(startedAt = Instant.now().toString()) else s
        if (services.redis.saveSession(stamped)) {
            call.respond(HttpStatusCode.Created, mapOf("ok" to true))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false, "degraded" to true, "error" to "redis unavailable, session not persisted"))
        }
    }

    get("/api/sessions/{id}") {
        val id = call.parameters["id"]!!
        val s = services.redis.getSession(id)
        if (s == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "no session"))
        else call.respond(s)
    }
}
