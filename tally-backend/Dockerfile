FROM maven:3.8.8-eclipse-temurin-8 AS build

WORKDIR /app

COPY pom.xml .
COPY src src

RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:8-jre

WORKDIR /app

COPY --from=build /app/target/*.war app.war

EXPOSE 9090

CMD ["java", "-jar", "/app/app.war"]
