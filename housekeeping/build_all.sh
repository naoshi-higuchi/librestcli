#!/bin/sh -v
cd $(dirname $0)/..
mvn clean install site:site
