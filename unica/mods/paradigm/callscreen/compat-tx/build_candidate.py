#!/usr/bin/env python3
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build an inactive diagnostic staging module. No boot overlay. Never modify firmware, ROM output, or a device."""
import argparse
import hashlib
import json
import os
import shutil
from pathlib import Path
import struct
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
# S23 (S918B/S911N) and Flip5 (F731N). Both PALs keep pal_device at 0x170
# bytes with the custom key at +0xf0 and the same device/stream enums, and
# both HALs import the same 29 PAL symbols.
HAL_SHAS = {'4ac2a7b0844088a7f1fae3207ccb8210cef2e1a480092536bb80a25acf21489a',
            '529035e82943ebd8b47dffa9150b34150e9630e050cb4f82521a4f458272284b'}
PAL_SHAS = {'51d2ea15bbc5dfb4361946845e552ecdd80d2842d96504701069781145280bbd',
            '3df00e47cf8dffe60d3ccff0fe0166a2a1087a88a5585eb46e59e1942f651a8a'}
FUNCTIONS = ['open', 'start', 'close', 'set_device', 'set_mute', 'set_param']

def require(ok, message):
    if not ok:
        raise ValueError(message)

def digest(data):
    return hashlib.sha256(data).hexdigest()

def dynamic_strings(data):
    require(data[:6] == b'\x7fELF\x02\x01', 'Expected little-endian ELF64')
    require(struct.unpack_from('<H', data, 18)[0] == 183, 'Expected AArch64')
    shoff = struct.unpack_from('<Q', data, 40)[0]
    entsize, count = struct.unpack_from('<HH', data, 58)
    sections = [struct.unpack_from('<IIQQQQIIQQ', data, shoff+i*entsize) for i in range(count)]
    dyn = next(s for s in sections if s[1] == 6)
    strings = sections[dyn[6]]
    needed = [v for tag, v in struct.iter_unpack('<qQ', data[dyn[4]:dyn[4]+dyn[5]]) if tag == 1]
    return strings[4], strings[5], needed

def patch_hal(data):
    require(digest(data) in HAL_SHAS, 'Unsupported HAL hash; refusing another firmware or patched input')
    base, size, needed = dynamic_strings(data)
    table = data[base:base+size]
    old = b'libar-pal.so\0'
    require(table.count(old) == 1 and table.index(old) in needed, 'Expected unique PAL DT_NEEDED')
    edits = [(base+table.index(old), old, b'libcs-pal.so\0')]
    # Unique imported names prevent a previously loaded global PAL from bypassing this shim.
    for suffix in FUNCTIONS:
        old = ('pal_stream_'+suffix+'\0').encode()
        new = ('cs__stream_'+suffix+'\0').encode()
        require(table.count(old) == 1, 'Missing/ambiguous PAL import '+suffix)
        edits.append((base+table.index(old), old, new))
    output = bytearray(data)
    for offset, old, new in edits:
        require(len(old) == len(new), 'String length mismatch')
        output[offset:offset+len(old)] = new
    require(len(data) == len(output), 'ELF size changed')
    # Every byte outside the seven explicitly measured dynamic strings must be untouched.
    allowed = {i for off, old, _ in edits for i in range(off, off+len(old))}
    require(all(a == b or i in allowed for i, (a, b) in enumerate(zip(data, output))), 'Unexpected byte change')
    return bytes(output), [{'offset':hex(off), 'from':old[:-1].decode(), 'to':new[:-1].decode()} for off, old, new in edits]

def firmware_dir(var):
    """Resolve out/fw/<MODEL>_<CSC> from a MODEL/CSC/IMEI build variable."""
    parts = os.environ.get(var, '').split('/')
    require(len(parts) >= 2 and parts[0] and parts[1], var+' is not set; source buildenv.sh <target> first')
    return Path(os.environ.get('FW_DIR') or ROOT/'out/fw')/(parts[0]+'_'+parts[1])

NDK_COMPILER = 'toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang'
NO_TOOLCHAIN = 4  # integrate_v6_rom.sh skips instead of failing the ROM build

def default_ndk():
    """First NDK that has the aarch64 API 33 clang: env vars, then SDK dirs."""
    candidates = [os.environ.get(v) for v in ('ANDROID_NDK_HOME', 'ANDROID_NDK_ROOT', 'ANDROID_NDK_LATEST_HOME', 'ANDROID_NDK')]
    for sdk in (os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT'),
                str(Path.home()/'Android/Sdk'), '/usr/local/lib/android/sdk'):
        if sdk and (Path(sdk)/'ndk').is_dir():
            candidates += sorted((str(p) for p in (Path(sdk)/'ndk').iterdir()), reverse=True)
    for ndk in candidates:
        if ndk and (Path(ndk)/NDK_COMPILER).is_file():
            return Path(ndk)
    return None

def ndk_compiler(ndk):
    compiler = ndk/NDK_COMPILER if ndk else None
    if not compiler or not compiler.is_file():
        import sys
        print('Android NDK not found (set ANDROID_NDK_HOME or pass --ndk)', file=sys.stderr)
        raise SystemExit(NO_TOOLCHAIN)
    return compiler

def device_arguments(parser):
    """Arguments shared by every builder; defaults come from the build config."""
    parser.add_argument('--device', default=os.environ.get('TARGET_CODENAME'),
                        help='Target codename the module is built for (default: $TARGET_CODENAME)')
    parser.add_argument('--vendor-fw', type=Path,
                        help='Firmware dir providing vendor/ (default: $FW_DIR/<TARGET_FIRMWARE model_csc>)')
    parser.add_argument('--system-fw', type=Path,
                        help='Firmware dir providing system/ (default: $FW_DIR/<SOURCE_FIRMWARE model_csc>)')

def resolve_device(args):
    require(args.device, 'Pass --device or set TARGET_CODENAME')
    args.vendor_fw = args.vendor_fw or firmware_dir('TARGET_FIRMWARE')
    args.system_fw = args.system_fw or firmware_dir('SOURCE_FIRMWARE')
    return args

def device_guard(var, action):
    """Shell check against the codename recorded in the module at build time."""
    return ('[ "$(getprop ro.product.device)" = "$(cat "$'+var+'/device")" ] || '
            +action+' "Built for $(cat "$'+var+'/device") only"')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='New directory under /tmp')
    parser.add_argument('--ndk', type=Path, default=default_ndk())
    device_arguments(parser)
    args = resolve_device(parser.parse_args())
    require(os.environ.get('SRC_DIR'), 'Source buildenv.sh <target> first')
    out = args.output.resolve()
    require(out.is_relative_to(Path('/tmp')) and not out.exists(), 'Output must be new and under /tmp')
    vendor = args.vendor_fw/'vendor'
    original = vendor/'lib64/hw/audio.primary.kalama.so'
    hal_sha = digest(original.read_bytes())
    pal_sha = digest((vendor/'lib64/libar-pal.so').read_bytes())
    require(pal_sha in PAL_SHAS, 'Unsupported PAL library')
    patched, edits = patch_hal(original.read_bytes())
    out.mkdir(parents=True)
    payload = out/'module'
    libs = payload/'candidate/vendor/lib64'
    (libs/'hw').mkdir(parents=True)
    (libs/'hw/audio.primary.kalama.so').write_bytes(patched)
    compiler = ndk_compiler(args.ndk)
    definitions = ['-Dpal_stream_'+s+'=cs__stream_'+s for s in FUNCTIONS]
    subprocess.run([str(compiler), '-std=c11', '-D_GNU_SOURCE', '-Wall', '-Wextra', '-Werror', '-O2',
                    '-fPIC', '-shared', '-Wl,-z,relro,-z,now,-z,max-page-size=16384',
                    '-Wl,-soname,libcs-pal.so', *definitions, str(HERE/'compat_tx.c'),
                    '-Wl,--no-as-needed', str(vendor/'lib64/libar-pal.so'), '-Wl,--as-needed',
                    '-Wl,--allow-shlib-undefined', '-ldl', '-llog', '-o', str(libs/'libcs-pal.so')], check=True)
    (payload/'module.prop').write_text('''id=rezoss_callscreen_compat_tx_stage
name=Call Screening TX diagnostic staging ONLY
version=0.2-inactive
versionCode=2
author=Rezoss
description=Inactive staged candidate. No system/vendor overlay or boot hooks. Action performs read-only checks only. No audio fix activated.
''')
    (payload/'device').write_text(args.device+'\n')
    (payload/'hal.sha256').write_text(hal_sha+'\n')
    (payload/'pal.sha256').write_text(pal_sha+'\n')
    (payload/'customize.sh').write_text('''#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
SKIPUNZIP=0
'''+device_guard('MODPATH', 'abort')+'''
EXPECTED='''+hal_sha+'''
ACTUAL=$(sha256sum /vendor/lib64/hw/audio.primary.kalama.so | cut -d ' ' -f 1)
[ "$ACTUAL" = "$EXPECTED" ] || abort "Wrong or already overlaid audio HAL. Remove competing audio module first."
PAL_EXPECTED='''+pal_sha+'''
PAL_ACTUAL=$(sha256sum /vendor/lib64/libar-pal.so | cut -d ' ' -f 1)
[ "$PAL_ACTUAL" = "$PAL_EXPECTED" ] || abort "Wrong PAL implementation"
[ -f /data/adb/modules/rezoss_callscreen_compat_tx_test/disable ] || \
    [ ! -d /data/adb/modules/rezoss_callscreen_compat_tx_test ] || abort "Disable failed v3 first"
ui_print "INACTIVE staging only. No boot overlay and no restart."
ui_print "After installation: run prepare_labels.sh, then the read-only Action check."
set_perm_recursive "$MODPATH/candidate/vendor" 0 0 0755 0644 u:object_r:vendor_file:s0
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/preflight.sh" 0 0 0755
set_perm "$MODPATH/prepare_labels.sh" 0 0 0755
''')
    (payload/'customize.sh').chmod(0o755)
    (payload/'skip_mount').touch()
    for name in ('preflight.sh', 'prepare_labels.sh'):
        shutil.copy2(HERE/name, payload/name)
    (payload/'action.sh').write_text('#!/system/bin/sh\n# SPDX-License-Identifier: GPL-3.0-or-later\nMODDIR=${0%/*}\nexec sh "$MODDIR/preflight.sh"\n')
    (payload/'action.sh').chmod(0o755)
    staged = sorted(p for p in (payload/'candidate').rglob('*') if p.is_file())
    (payload/'payload.sha256').write_text(''.join(digest(p.read_bytes())+'  '+str(p.relative_to(payload))+'\n' for p in staged))
    for unsafe in ('system', 'vendor', 'post-fs-data.sh', 'service.sh', 'metamount.sh', 'boot-completed.sh'):
        require(not (payload/unsafe).exists(), 'Active mount/boot payload forbidden: '+unsafe)
    report = {'status':'INACTIVE STAGING ONLY; NO BOOT OVERLAY; NOT DEVICE-VERIFIED', 'source_hal_sha256':hal_sha, 'source_pal_sha256':pal_sha,
              'candidate_hal_sha256':digest(patched), 'dynamic_string_edits':edits,
              'shim_sha256':digest((libs/'libcs-pal.so').read_bytes()),
              'source_sha256':digest((HERE/'compat_tx.c').read_bytes()),
              'scope':'PAL voice type14 exact proxy pair only; RX stays proxy, TX handset with mandatory mute',
              'runtime_unknowns':['ZeroMount overlay/new-file visibility and Weaver failure isolation','Post-install SELinux labels','Android linker loading','PAL pre-start mute support','mixed proxy RX/handset TX graph startup',
                                  'caller speech injection and ASR','mute isolation during speech and transitions','route changes and emergency-call regression']}
    (out/'build-report.json').write_text(json.dumps(report, indent=2)+'\n')
    archive = out/'rezoss-callscreen-compat-tx-stage-v4.zip'
    with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED) as z:
        for path in sorted(payload.rglob('*')):
            if path.is_file():
                z.write(path, path.relative_to(payload))
    print(json.dumps({'module':str(archive), 'sha256':digest(archive.read_bytes()), 'report':str(out/'build-report.json')}, indent=2))

if __name__ == '__main__':
    main()
