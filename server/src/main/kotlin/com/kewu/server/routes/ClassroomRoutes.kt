package com.kewu.server.routes

import com.kewu.server.ServicesKey
import com.kewu.server.model.CheckInRecord
import com.kewu.server.model.CheckInView
import com.kewu.server.model.ClassNote
import com.kewu.server.model.ClassroomSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant

// 课堂缓存相关：纪要、签到、会话三块，全走 Redis
fun Routing.classroomRoutes() {
    val services = application.attributes[ServicesKey]

    post("/api/notes") {
        val note = call.receive<ClassNote>()
        val stamped = if (note.updatedAt.isBlank()) note.copy(updatedAt = Instant.now().toString()) else note
        services.redis.cacheClassNotes(stamped.classId, stamped.content)
        call.respond(mapOf("ok" to true))
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
        services.redis.setCheckIn(stamped.classId, stamped.studentId, stamped.status)
        call.respond(mapOf("ok" to true))
    }

    get("/api/checkin/{classId}") {
        val classId = call.parameters["classId"]!!
        call.respond(CheckInView(classId, services.redis.getCheckIns(classId)))
    }

    post("/api/sessions") {
        val s = call.receive<ClassroomSession>()
        val stamped = if (s.startedAt.isBlank()) s.copy(startedAt = Instant.now().toString()) else s
        services.redis.saveSession(stamped)
        call.respond(HttpStatusCode.Created, mapOf("ok" to true))
    }

    get("/api/sessions/{id}") {
        val id = call.parameters["id"]!!
        val s = services.redis.getSession(id)
        if (s == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "no session"))
        else call.respond(s)
    }
}
