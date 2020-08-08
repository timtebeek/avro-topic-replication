#!/bin/bash
set -e;

version=5.5.1

function download_and_install {
  dir=$1-$version
  zip=$dir.zip
  if [ ! -d $dir ]; then
  	[ ! -f $zip ] && wget https://github.com/confluentinc/$1/archive/v$version.zip --output-document $zip
  	unzip $zip
    cd $dir
    mvn install
    cd ..
  fi
}

download_and_install common
download_and_install schema-registry
chmod +x schema-registry-$version/bin/kafka-avro-console-*

