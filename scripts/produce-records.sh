#!/bin/bash
set -e;

schema=`jq -c < src/test/resources/com.foo.Foo.avsc`
cd schema-registry-*/;

# As per: https://docs.confluent.io/current/schema-registry/serdes-develop/serdes-avro.html
echo '{"id": "12345", "name": null}' | \
bin/kafka-avro-console-producer \
 --broker-list $1 \
 --property schema.registry.url=http://$2 \
 --topic $3 \
 --property value.schema="$schema"

