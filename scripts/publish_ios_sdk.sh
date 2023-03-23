#!/bin/bash

set -e

export MAESTRO_SDK_VERSION=$(grep VERSION_NAME gradle.properties | cut -d'=' -f2)

pod trunk push --allow-warnings
