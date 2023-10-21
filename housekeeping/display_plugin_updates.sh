#!/bin/sh -v
cd $(dirname $0)/..
mvn versions:display-plugin-updates
