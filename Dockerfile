# ---- Build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && cp target/spendwitz-*.jar app.jar
# Unpack the fat jar: classes load faster from plain jars than from nested ones
RUN java -Djarmode=tools -jar app.jar extract --destination extracted

# ---- Run ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 spring
USER spring
COPY --from=build /app/extracted/lib lib
COPY --from=build /app/extracted/app.jar app.jar

# Tuned for small free-tier containers (~512 MB RAM, a fraction of a CPU).
# TieredStopAtLevel=1 uses only the fast C1 compiler: quicker startup and less memory, plenty for low traffic.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:ReservedCodeCacheSize=48m -XX:MaxMetaspaceSize=160m -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar app.jar"]
