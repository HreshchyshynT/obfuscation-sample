#!/bin/bash

# Exit immediately if a command exits with a non-zero status
set -e

echo "🧹 Starting Gradle clean..."

# Check if the Gradle wrapper exists in the current directory
if [ -f "./gradlew" ]; then
    ./gradlew clean
else
    echo "❌ Gradle wrapper not found"
    exit 1
fi

echo "✅ Gradle clean completed successfully."
echo "🚀 Calling uninstall_all"

# Replace 'path/to/your/other_script.sh' with the actual path to your script
./scripts/uninstall_all.sh


echo "🎉 All done!"
