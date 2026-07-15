FROM eclipse-temurin:21-jre-alpine

ENV LANG=C.UTF-8

RUN set -eux; \
    apk -U upgrade; \
    apk add bash curl ca-certificates findutils coreutils gettext pwgen procps tini; \
    rm -rf /var/cache/apk/*

ENV FUSEKI_VERSION=6.1.0
ENV FUSEKI_SHA512=75457f45d14397876a41ed51abe7ae5d2f1e708dfe1315765f858158bc5c6813bc036ec1539ddc4dffd26201f5cc31fadec299ca5c3dc2548b723513ed31d326
ENV ASF_MIRROR=https://www.apache.org/dyn/mirrors/mirrors.cgi?action=download&filename=
ENV ASF_ARCHIVE=https://archive.apache.org/dist/
ENV FUSEKI_BASE=/fuseki
ENV FUSEKI_HOME=/jena-fuseki

LABEL org.opencontainers.image.version="6.1.0"
LABEL org.opencontainers.image.title="Apache Jena Fuseki"
LABEL org.opencontainers.image.description="Fuseki SPARQL 1.1 server backed by Apache Jena TDB2"
LABEL org.opencontainers.image.url="https://github.com/stain/jena-docker/tree/master/jena-fuseki"
LABEL org.opencontainers.image.source="https://github.com/stain/jena-docker/"
LABEL org.opencontainers.image.documentation="https://jena.apache.org/documentation/fuseki2/"
LABEL org.opencontainers.image.licenses="(Apache-2.0 AND (GPL-2.0 WITH Classpath-exception-2.0) AND GPL-3.0)"
LABEL org.opencontainers.image.authors="Apache Jena Fuseki by https://jena.apache.org/"

WORKDIR /tmp

RUN echo "$FUSEKI_SHA512  fuseki.tar.gz" > fuseki.tar.gz.sha512 && \
    (curl --location --silent --show-error --fail --retry-connrefused --retry 3 \
        --output fuseki.tar.gz \
        "${ASF_MIRROR}jena/binaries/apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz" || \
     curl --fail --silent --show-error --retry-connrefused --retry 3 \
        --output fuseki.tar.gz \
        "${ASF_ARCHIVE}/jena/binaries/apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz") && \
    sha512sum -c fuseki.tar.gz.sha512 && \
    tar zxf fuseki.tar.gz && \
    mv apache-jena-fuseki* $FUSEKI_HOME && \
    rm fuseki.tar.gz* && \
    cd $FUSEKI_HOME && rm -rf fuseki.war && chmod 755 fuseki-server

COPY shiro.ini $FUSEKI_HOME/shiro.ini
COPY docker-entrypoint.sh /
RUN chmod 755 /docker-entrypoint.sh

COPY load.sh $FUSEKI_HOME/
COPY tdbloader2 $FUSEKI_HOME/
RUN chmod 755 $FUSEKI_HOME/load.sh $FUSEKI_HOME/tdbloader2

RUN addgroup -S fuseki && \
    adduser -G fuseki -S -D -H fuseki && \
    chown -R fuseki:fuseki $FUSEKI_HOME && \
    mkdir -p $FUSEKI_BASE && \
    chown -R fuseki:fuseki $FUSEKI_BASE

VOLUME $FUSEKI_BASE

WORKDIR $FUSEKI_HOME

EXPOSE 3030

USER fuseki

ENTRYPOINT ["/sbin/tini", "--", "sh", "/docker-entrypoint.sh"]

CMD ["/jena-fuseki/fuseki-server"]
