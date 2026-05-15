#!/bin/zsh

# Define the package IDs to remove
PKG1="com.example.obfuscation_sample_a"
PKG2="com.example.obfuscation_sample_b"

# Function to uninstall a package
uninstall_pkg() {
    local pkg=$1
    print -P "%F{blue}Checking for $pkg...%f"
    
    # Check if the package is installed
    if adb shell pm list packages | grep -q "$pkg"; then
        print -P "%F{yellow}Uninstalling $pkg...%f"
        # Removes system or third-party app for the current user
        if adb shell pm uninstall -k --user 0 "$pkg" | grep -q "Success"; then
            print -P "%F{green}✔ Successfully removed $pkg%f"
        else
            print -P "%F{red}✘ Failed to uninstall $pkg%f"
        fi
    else
        print -P "%F{magenta}⚠ $pkg is not installed on this device.%f"
    fi
}

# Check if ADB device is connected
if ! adb devices | grep -v "List of devices" | grep -q "device"; then
    print -P "%F{red}Error: No ADB device detected. Connect your phone and enable USB Debugging.%f"
    exit 1
fi

# Execute uninstallation
uninstall_pkg "$PKG1"
uninstall_pkg "$PKG2"

print -P "%F{green}Process finished.%f"
