FROM registry.cn-hangzhou.aliyuncs.com/dockerhub_mirror/java:21-anolis

MAINTAINER wangiegie@gmail.com

ENV TZ=Asia/Shanghai
ENV LANG C.UTF-8
ENV JAVA_OPTS="-Xms512m -Xmx1024m -Djava.security.egd=file:/dev/./urandom"

RUN ln -sf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

RUN mkdir -p /ai-work-baas

WORKDIR /ai-work-baas

EXPOSE 4010

ADD ./target/ai-work-baas.jar ./

CMD sleep 30;java $JAVA_OPTS -jar ai-work-baas.jar
