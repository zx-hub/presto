#!/bin/bash

set -euo pipefail

if test $# -gt 0; then
    echo "$0 does not accept arguments" >&2
    exit 32
fi

set -x

tar xf /docker/presto-server.tar.gz -C /docker

if test -d /docker/presto-init.d; then
    for init_script in /docker/presto-init.d/*; do
        "${init_script}"
    done
fi

export JAVA_HOME="/usr/lib/jvm/zulu-11"
export PATH="${JAVA_HOME}/bin:${PATH}"

exec /docker/presto-server-*/bin/launcher \
  -Dnode.id="${HOSTNAME}" \
  --etc-dir="/docker/presto-product-tests/conf/presto/etc" \
  --data-dir=/var/presto \
  run
