# Extends the existing passbolt-android-build image (Temurin 21 JDK + Android cmdline-tools)
# with the SDK 37 platform and build-tools that Lightning-Browser requires.
FROM passbolt-android-build:latest

USER ci-build

RUN export PATH=$PATH:$ANDROID_HOME/cmdline-tools/bin \
    && yes | sdkmanager --sdk_root=$ANDROID_HOME --licenses > /dev/null 2>&1 || true \
    && sdkmanager --sdk_root=$ANDROID_HOME \
        "build-tools;37.0.0" \
        "platforms;android-37.1" \
        "platforms;android-37.0" \
    && ls $ANDROID_HOME/platforms $ANDROID_HOME/build-tools

WORKDIR /application
