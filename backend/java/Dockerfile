FROM dockerhub.rnd.amadeus.net:5002/lhmdw/base-images/ubi9/openjdk-21-runtime:1.24-2.1774011801
COPY target/reservation-api-0.1.0.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
