# Build stage: compile + shade into a fat jar
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# Run stage: slim JRE, app listens on $PORT (default 8080)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/resiliency-tests-1.0-SNAPSHOT.jar app.jar
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
