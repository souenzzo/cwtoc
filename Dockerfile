FROM clojure:openjdk-18-tools-deps-alpine AS clojure
RUN adduser -D cwtoc
USER cwtoc
WORKDIR /home/cwtoc
COPY --chown=cwtoc ./deps.edn ./
RUN clojure -A:dev -P && clojure -P --report stderr
COPY --chown=cwtoc . .
ARG VERSION=develop
RUN clojure -A:dev -J-Dcwtoc.version=$VERSION -M -m cwtoc.build

FROM openjdk:18-jdk-alpine
RUN adduser -D cwtoc
USER cwtoc
WORKDIR /home/cwtoc
CMD java \
  -Dcwtoc.server.http-port="$PORT" \
  -Dcwtoc.server.cwtoc-db-url="$DATABASE_URL" \
  -jar /usr/share/cwtoc/cwtoc.jar
COPY --from=clojure /home/cwtoc/target/cwtoc.jar /usr/share/cwtoc/cwtoc.jar
