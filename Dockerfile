FROM eclipse-temurin:17-jdk AS builder

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src

RUN chmod +x mvnw \
    && ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:17-jre

WORKDIR /app

RUN addgroup --system seckill \
    && adduser --system --ingroup seckill seckill

COPY --from=builder /workspace/target/*.jar app.jar

USER seckill

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
