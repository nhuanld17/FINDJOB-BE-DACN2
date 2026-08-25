# syntax=docker/dockerfile:1

# ============================================================
# Stage 1: BUILD — Maven + JDK 21
#   - Copy pom.xml trước + tải dependencies (tận dụng layer cache)
#   - --mount=type=cache để cache ~/.m2 giữa các lần build (BuildKit)
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Cache dependencies: chỉ copy pom trước khi copy source
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests dependency:go-offline

# Copy source + build jar (test sẽ chạy riêng trong CI/CD)
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

# ============================================================
# Stage 2: RUNTIME — JRE 21 (Ubuntu base, ổn định hơn alpine
#   cho các thư viện native như PDFBox/POI)
#   - Chạy bằng user non-root (best practice bảo mật)
#   - Chỉ copy jar, không mang theo Maven/source
# ============================================================
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Tạo user non-root
RUN groupadd --system spring && useradd --system --gid spring spring
USER spring:spring

COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
