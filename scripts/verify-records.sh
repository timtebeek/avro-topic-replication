#!/bin/bash
set -e;

cd schema-registry-*/;

# As per: https://docs.confluent.io/current/schema-registry/serdes-develop/serdes-avro.html
bin/kafka-avro-console-consumer \
 --bootstrap-server $1 \
 --topic $3 \
 --from-beginning \
 --property schema.registry.url=http://$2

