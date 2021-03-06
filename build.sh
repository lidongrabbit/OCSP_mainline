#!/usr/bin/env bash

set -e

HOME_PATH=$(cd `dirname $0`; pwd)
version=2.0.1

cd ${HOME_PATH}

mvn clean package -Dmaven.test.skip=true -Pspark-1.6

if [ $? -ne 0 ]; then
   echo "Build failed..."
   exit 1
fi


rm -fr build

mkdir -p build/OCDP_Stream/lib
mkdir -p build/OCDP_Stream/logs
mkdir -p build/OCDP_Stream/web

cp -r bin build/OCDP_Stream
cp -r conf build/OCDP_Stream

cp core/target/core-${version}.jar build/OCDP_Stream/lib
cp core/target/core-${version}-dist/lib/*.jar build/OCDP_Stream/lib

tar -xzf web/target/web-${version}-bundle.tar.gz -C build/OCDP_Stream/web

cd build

tar -czf OCDP_Stream_${version}.tar.gz OCDP_Stream

exit 0
