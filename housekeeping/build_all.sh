#!/bin/sh -v
cd ..
mvn clean install javadoc:javadoc jacoco:report
