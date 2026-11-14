FROM docker:27.5.1-cli AS docker
FROM gradle:8.14.3-jdk17
USER root
COPY --from=docker /usr/local/bin/docker /usr/local/bin/docker
WORKDIR /workspace
CMD ["sleep", "infinity"]
