# Runtime image for the Spring app. CI builds backend/build/libs/graffiti.jar first (12a decision 1).
FROM eclipse-temurin:21.0.11_10-jre-noble

RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --chown=app:app backend/build/libs/graffiti.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
