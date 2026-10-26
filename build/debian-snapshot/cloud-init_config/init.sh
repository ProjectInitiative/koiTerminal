#!/bin/bash

set -ex

SCRIPT_PATH=$(readlink -f -- "${0}")
SCRIPT_DIR=$(dirname -- ${SCRIPT_PATH});

LOCALDEBS="${SCRIPT_DIR}/localdebs"
LOCALFILES="${SCRIPT_DIR}/root_files"

install_localdebs() {
	if [ -d "$LOCALDEBS" ]; then
		find "$LOCALDEBS" -name "*.deb" -print0 | xargs -0 -r dpkg -i
	fi
}

_copy_files() {
	cp -vR "${LOCALFILES}"/* /
	# Auto-login droid on the serial console (koiTerminal opens console_in).
	sed -i -e "s;agetty -o ';agetty -a droid -o '-f ;g" /usr/lib/systemd/system/serial-getty@.service
}

_restart_services() {
	# NOTE: mnt-internal / mnt-shared virtiofs units are deliberately NOT
	# enabled — koiTerminal's vm_config uses sharedPath: [] so the host never
	# provides the shared-memory context and those mounts can never work
	# (same root cause as the nofail fix in the nixos-avf image). A single
	# failing unit must not abort the rest, hence the per-unit tolerance.
	CONFIG_CHANGED_SERVICES=(
		attach-cidata.service
		avahi_ttyd.service
		backup_mount.service
		ttyd.service
		ttyd_uds.service
		ttyd_vsock_bridge.service
		linux_vm_manager.service
	)
	for svc in "${CONFIG_CHANGED_SERVICES[@]}"; do
		systemctl enable --now "$svc" || echo "WARN: failed to enable $svc" >&2
	done
}

apply_avf_configs() {
	_copy_files
	_restart_services
}

install_localdebs
apply_avf_configs
