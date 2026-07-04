FROM registry.cn-hangzhou.aliyuncs.com/dockerhub_mirror/mysql-server:8.0.32

MAINTAINER lengleng(wangiegie@gmail.com)

ENV TZ=Asia/Shanghai
ENV LANG C.UTF-8

RUN ln -sf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY ./ai_work.sql /docker-entrypoint-initdb.d

COPY ./ai_work_config.sql /docker-entrypoint-initdb.d
