#!/bin/sh -v
cd $(dirname $0)/..
mvn versions:use-latest-releases
