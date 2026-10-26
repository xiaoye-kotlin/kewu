package com.kewu.modules.user.routes

import com.kewu.models.ApiResponse
import com.kewu.modules.course.models.CourseInfo
import com.kewu.modules.user.models.*
import com.kewu.modules.user.service.UserService
import com.kewu.security.JwtConfig
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

// 用户模块的接口: 注册、登录、绑课、查资料
fun Routing.userRoutes(userService: UserService) {
    route("/api/user") {
        post("/register") {
            val req = call.receive<RegisterRequest>()
            userService.register(req).fold(
                onSuccess = { call.respond(ApiResponse<UserInfo>(0, "注册成功", it)) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "注册失败")) }
            )
        }

        post("/login") {
            val req = call.receive<LoginRequest>()
            userService.login(req).fold(
                onSuccess = { call.respond(ApiResponse<LoginResponse>(0, "登录成功", it)) },
                onFailure = { call.respond(HttpStatusCode.Unauthorized, ApiResponse<String>(1, it.message ?: "登录失败")) }
            )
        }

        authenticate(JwtConfig.NAME) {
            post("/bind-course") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim(JwtConfig.CLAIM_USER_ID).asString()
                val req = call.receive<BindCourseRequest>()
                userService.bindCourse(userId, req.courseId).fold(
                    onSuccess = { call.respond(ApiResponse<CourseInfo>(0, "绑定成功", it)) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "绑定失败")) }
                )
            }

            // 修改个人资料（昵称 / 学校 / 班级），2026-10-26
            post("/profile") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim(JwtConfig.CLAIM_USER_ID).asString()
                val req = call.receive<UpdateProfileRequest>()
                userService.updateProfile(userId, req).fold(
                    onSuccess = { call.respond(ApiResponse(0, "已保存", it)) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "保存失败")) }
                )
            }

            get("/me") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim(JwtConfig.CLAIM_USER_ID).asString()
                call.respond(
                    ApiResponse<UserProfileResponse>(
                        0, "ok", UserProfileResponse(
                            user = userService.getProfile(userId),
                            courses = userService.getBoundCourses(userId),
                            classes = com.kewu.modules.roster.RosterService.joinedClasses(userId)
                        )
                    )
                )
            }
        }
    }
}
