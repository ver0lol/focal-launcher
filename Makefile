PYTHON ?= python3
NODE ?= node
SDK_DIR ?= $(CURDIR)/work/android-sdk
BUILD_DIR ?= $(CURDIR)/work/launcher-build
JAVA_HOME ?=
KEYSTORE ?=
ADB ?= adb
SFA_APK ?= $(CURDIR)/work/singbox/SFA-1.14.4-armeabi-v7a.apk
DEVICE_CONFIG ?= $(CURDIR)/local/device.json
VPN_CONFIG ?= $(CURDIR)/local/vpn.json
-include local/build.mk
VERSION := $(shell $(PYTHON) -c 'import xml.etree.ElementTree as E; print(E.parse("launcher/AndroidManifest.xml").getroot().get("{http://schemas.android.com/apk/res/android}versionName"))')

.PHONY: help check check-python build bootstrap vpn install update
help:
	@echo 'make check       Offline repository and web-client checks; no ADB'
	@echo 'make build       Build focal-launcher into dist/; no device access'
	@echo 'make bootstrap   Download pinned Google tools (Linux/macOS) and build focal-launcher'
	@echo './focal update   Prepare Arch dependencies, check, build and install with original key'
	@echo 'make vpn         Build optional VPN overlay using local/vpn.json'
	@echo 'make install     Install focal-launcher on the identity-pinned device; no reboot'
check:
	$(PYTHON) scripts/check_repo.py
	$(PYTHON) -m unittest discover -s scripts/qa -p 'test_*.py'
	"$(NODE)" launcher/qa/check_remote_client.cjs
check-python:
	$(PYTHON) scripts/check_repo.py
	$(PYTHON) -m unittest discover -s scripts/qa -p 'test_*.py'
build:
	$(PYTHON) launcher/build.py --sdk "$(SDK_DIR)" --work "$(BUILD_DIR)" $(if $(JAVA_HOME),--java-home "$(JAVA_HOME)") $(if $(KEYSTORE),--keystore "$(KEYSTORE)")
bootstrap:
	$(PYTHON) launcher/build.py --bootstrap --sdk "$(SDK_DIR)" --work "$(BUILD_DIR)" $(if $(JAVA_HOME),--java-home "$(JAVA_HOME)") $(if $(KEYSTORE),--keystore "$(KEYSTORE)")
vpn:
	$(PYTHON) singbox-integration/build.py --sdk "$(SDK_DIR)" $(if $(JAVA_HOME),--java-home "$(JAVA_HOME)") --base-apk "$(SFA_APK)" --keystore "$(if $(KEYSTORE),$(KEYSTORE),$(if $(wildcard local/signing/rice-development.jks),$(CURDIR)/local/signing/rice-development.jks,$(BUILD_DIR)/rice-development.jks))" --vpn-config "$(VPN_CONFIG)"
install:
	$(PYTHON) launcher/install.py --sdk "$(SDK_DIR)" $(if $(JAVA_HOME),--java-home "$(JAVA_HOME)") --adb "$(ADB)" --device-config "$(DEVICE_CONFIG)" --apk "$(CURDIR)/dist/focal-launcher-$(VERSION).apk"
update:
	$(PYTHON) scripts/workflow.py update --sdk "$(SDK_DIR)" --work "$(BUILD_DIR)" $(if $(JAVA_HOME),--java-home "$(JAVA_HOME)") $(if $(KEYSTORE),--keystore "$(KEYSTORE)") --adb "$(ADB)" --device-config "$(DEVICE_CONFIG)"
