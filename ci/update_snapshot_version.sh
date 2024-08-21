#!/bin/bash
set -e
set -x

PACKAGE_VERSION=$(cat ./package.json | jq -r '.version')
>&2 echo "current version: $PACKAGE_VERSION"

SNAPSHOT_VERSION=$(./ci/increment_semver.sh -p $PACKAGE_VERSION)"-SNAPSHOT"
>&2 echo "updating snapshot version to $SNAPSHOT_VERSION"

# sed command works only on linux based systems as macOS version expects a backup file passed additionally
if [ "$(uname)" == "Darwin" ]; then
  ARGS=('')
else
  ARGS=()
fi

sed -i "${ARGS[@]}" -e "/VERSION_NAME=/ s/=.*/=$SNAPSHOT_VERSION/" ./gradle.properties

echo $SNAPSHOT_VERSION