#!/bin/bash
set -e
# Setting up parent path to make the script executable from anywhere.
echo "Setting up parent_path..."
parent_path=$( cd "$(dirname "${BASH_SOURCE[0]}")" ; pwd -P )
echo "Parent path configured : ${parent_path}"
cd "$parent_path"

# Defining mount directories between host and container
BUILD_DIR="${PWD}"/../../../../../router/target/mgw-wasm/
mkdir -p "${BUILD_DIR}"

bazel build -c opt //:mgw-websocket.wasm
cp -a bazel-bin/mgw-websocket.wasm $BUILD_DIR

chmod a+rwx "${PWD}"/../../../../../router/target/mgw-wasm/mgw-websocket.wasm
