FROM gradle:jdk17
WORKDIR /home/gradle/app
ADD . .
ARG module
RUN gradle clean build

FROM eclipse-temurin:17
ARG module
WORKDIR /home/java
COPY --from=0 /home/gradle/app/build/libs/*.jar app.jar
COPY --from=0 /home/gradle/app/src/main/resources/opentelemetry-javaagent.jar opentelemetry-javaagent.jar

CMD java -javaagent:/home/java/opentelemetry-javaagent.jar -jar ./app.jar