#FROM redis:stretch
FROM ubuntu:bionic

ENV SBT_VERSION 1.2.8
ENV SBT_HOME /usr/local/sbt
ENV PATH ${PATH}:${SBT_HOME}/bin
ENV RUNLEVEL=1

RUN apt-get update -q && apt install -y software-properties-common gnupg git bash curl openssl tar psmisc
# Install Zulu 11: https://www.linuxuprising.com/2019/04/install-latest-openjdk-12-11-or-8-in.html
RUN apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys 0xB1998361219BD9C9
RUN apt-add-repository 'deb http://repos.azulsystems.com/ubuntu stable main'
RUN apt-get update -q
RUN apt install -y zulu-11
# Install redis
RUN apt install -y redis-server && sed -i 's/bind 127.0.0.1 ::1/# bind 127.0.0.1 ::1/g' /etc/redis/redis.conf && sed -i 's/protected-mode yes/protected-mode no/g' /etc/redis/redis.conf

RUN curl -sL "https://piccolo.link/sbt-$SBT_VERSION.tgz" | gunzip | tar -x -C /usr/local --strip-components=1 && \
    echo -ne "- with sbt $SBT_VERSION\n" >> /root/.built

WORKDIR /app