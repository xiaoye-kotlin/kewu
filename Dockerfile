# kewu 后端镜像：两阶段构建
# 第一阶段用完整 Gradle 环境把 :server 装成可分发的 install 目录，第二阶段只留 JRE 运行时
FROM gradle:8.10.2-jdk21 AS build
USER root
WORKDIR /build
COPY . .
RUN gradle :server:installDist --no-daemon --console=plain

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/server/build/install/server/ ./
# 端口可在部署时用环境变量 PORT 覆盖，不设就是 8080
EXPOSE 8080
ENTRYPOINT ["bin/server"]
