FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY app/.mvn .mvn
COPY app/mvnw app/pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY app/src src
RUN ./mvnw -B -DskipTests package && cp target/seatlock-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 seatlock
USER seatlock
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]
