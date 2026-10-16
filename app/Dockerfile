# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B clean package -DskipTests


FROM eclipse-temurin:25-jre-noble AS runtime

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-venv curl ca-certificates tini \
    && rm -rf /var/lib/apt/lists/*


ARG DOCKER_CLI_VERSION=27.3.1
RUN arch="$(uname -m)" \
    && curl -fsSL "https://download.docker.com/linux/static/stable/${arch}/docker-${DOCKER_CLI_VERSION}.tgz" \
       | tar -xz -C /usr/local/bin --strip-components=1 docker/docker \
    && docker --version

WORKDIR /app
ENV SVC=/app/python-services/identity-verification

COPY python-services/identity-verification/requirements.txt ${SVC}/requirements.txt
RUN python3 -m venv ${SVC}/.venv \
    && ${SVC}/.venv/bin/pip install --no-cache-dir --upgrade pip \
    && ${SVC}/.venv/bin/pip install --no-cache-dir -r ${SVC}/requirements.txt


RUN mkdir -p ${SVC}/models \
    && curl -fsSL -o ${SVC}/models/face_detection_yunet.onnx \
       "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx" \
    && curl -fsSL -o ${SVC}/models/face_recognition_sface.onnx \
       "https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx"

COPY python-services/identity-verification/app.py python-services/identity-verification/face_utils.py ${SVC}/
COPY docker/sandbox-python/ /app/docker/sandbox-python/
COPY docker/sandbox-node/   /app/docker/sandbox-node/
COPY docker/sandbox-java/   /app/docker/sandbox-java/
COPY docker/entrypoint.sh   /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

COPY --from=build /build/target/labs-*.jar /app/app.jar

RUN ${SVC}/.venv/bin/python -c "import sys; sys.path.insert(0, '${SVC}'); import face_utils" \
    && echo "face models load OK"

EXPOSE 8080
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint.sh"]
