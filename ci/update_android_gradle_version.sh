#!/bin/bash
set -e
set -x

PACKAGE_VERSION=$(cat ./package.json | jq -r '.version')
echo "updating gradle version name to $PACKAGE_VERSION"
# sed command works only on linux based systems as macOS version expects a backup file passed additionally

if [ "$(uname)" == "Darwin" ]; then
  ARGS=('')
else
  ARGS=()
fi

sed -i "${ARGS[@]}" -e "/VERSION_NAME=/ s/=.*/=$PACKAGE_VERSION/" ./gradle.properties
sed -i "${ARGS[@]}" -e '/def livekit_version =/ s/".*"/"'"$PACKAGE_VERSION"'"/' ./README.md
