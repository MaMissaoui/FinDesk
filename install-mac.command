#!/bin/bash
# Double-click in Finder to build the release checked out here and install it into /Applications.
# It refuses uncommitted or unreleased code; pass -Pforce to install it anyway.
# A previous FinDesk.app goes to the Trash. Quit FinDesk first. Needs a JDK (21+) for the build.
# Same as running `./gradlew installMacApp` in this folder; extra arguments go to Gradle (e.g. -PinstallDir=<folder>).

cd "$(dirname "$0")" || exit 1

finish() {
    echo
    read -r -n 1 -s -p "Press any key to close this window."
    echo
}
trap finish EXIT

echo "Building and installing FinDesk — the first build takes a few minutes."
echo

if ! ./gradlew --console=plain --quiet installMacApp "$@"; then
    echo
    echo "Installation failed; see the messages above. Nothing in /Applications was changed unless they say so."
    exit 1
fi

echo
echo "Done. Start FinDesk from Applications, Launchpad or Spotlight."
