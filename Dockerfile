# ========= 1. 构建阶段 =========
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java","-jar","app.jar","--spring.profiles.active=prod"]