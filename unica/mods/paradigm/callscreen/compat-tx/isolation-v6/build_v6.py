#!/usr/bin/env python3
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build an inactive exact-firmware v6 test module. Never deploy or reboot."""
import argparse
import hashlib
import importlib.util
import json
import os
import sys
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[5]
CONFIG = 'vendor/etc/audio/sku_kalama'
KEY = 'rezoss_cs_isolated'
PATH = 'rezoss-call-screen-isolated-mic'
TINYALSA = 'system/system/lib64/libtinyalsa.so'
TINYALSA_SHA = '2503734a6b1b18602dea0cc472a1ff8751eeb4af513f10b59407e344784de84c'
# Known-good stock inputs. The HAL is binary-patched at measured offsets and
# the PAL struct layout is pinned in compat_tx.c, so only exact builds are
# accepted. mixer_paths.xml only needs the TX controls used below, which are
# equal across the listed variants (S918B, S911N KSU8GZIF and F731N).
PINS = {
    'vendor/lib64/hw/audio.primary.kalama.so': {'4ac2a7b0844088a7f1fae3207ccb8210cef2e1a480092536bb80a25acf21489a',
                                                '529035e82943ebd8b47dffa9150b34150e9630e050cb4f82521a4f458272284b'},
    'vendor/lib64/libar-pal.so': {'51d2ea15bbc5dfb4361946845e552ecdd80d2842d96504701069781145280bbd',
                                  '3df00e47cf8dffe60d3ccff0fe0166a2a1087a88a5585eb46e59e1942f651a8a'},
    CONFIG+'/mixer_paths.xml': {'bb7aa21698ea6ddcfd4304fd20a77e94524f7f0b647b358fe28c825c2ea5ed71',
                                '9ece3898dd32f44da5cb9b33c1f4ce3f5b3c7d1d5420a3383136af246d997a49',
                                'fda2e5a6acfdbefd31ef7ccc6d3bd951b0d7e1dca5f9f26d9e40de2dc90025b4'},
    # S23 (4-channel handset voice capture) and Flip5 F731N (2-channel).
    CONFIG+'/resourcemanager.xml': {'4de0c8c242cab3bb01ea2dbf32cc1a4483397248c022f0b764c17c8e97e857da',
                                    '70278156e0f00f884c92eda47647844cb4a1106aaa5534bc23a36153ca20c57f'},
}
UNSUPPORTED = 3  # integrate_v6_rom.sh skips instead of failing the ROM build

def unsupported(reason):
    print('Unsupported firmware: '+reason, file=sys.stderr)
    raise SystemExit(UNSUPPORTED)

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def patched_configs(vendor):
    # Text insertion preserves all unrelated XML bytes/comments and ordering.
    rm = (vendor/'etc/audio/sku_kalama/resourcemanager.xml').read_text()
    tree = ET.fromstring(rm)
    handset = [d for d in tree.findall('./device_profile/in-device') if d.findtext('id') == 'PAL_DEVICE_IN_HANDSET_MIC']
    assert len(handset) == 1
    voice = [u for u in handset[0].findall('usecase') if u.findtext('name') == 'PAL_STREAM_VOICE_CALL']
    assert len(voice) == 1 and voice[0].findtext('channels') in ('2', '4')
    # One silent decimator lane per capture channel (S23: 4, Flip5: 2).
    lanes = int(voice[0].findtext('channels'))
    assert not tree.findall(f'.//custom-config[@key="{KEY}"]')
    marker = '                <snd_device_name>call-handset-mic</snd_device_name>\n'
    assert rm.count(marker) == 1
    rm = rm.replace(marker, marker+f'''                <custom-config key="{KEY}">
                    <snd_device_name>{PATH}</snd_device_name>
                </custom-config>
''')
    mixer = (vendor/'etc/audio/sku_kalama/mixer_paths.xml').read_text()
    assert PATH not in mixer and mixer.count('</mixer>') == 1
    controls = ''.join(f'        <ctl name="TX {bank} MUX{i}" value="ZERO" />\n' for bank in ('DMIC','SMIC') for i in range(8))
    # Keep the digital capture lanes clocked, sourcing silence rather
    # than referencing main-mic/multi-mic (which enable physical sources).
    controls += ''.join(f'        <ctl name="TX_AIF1_CAP Mixer DEC{i}" value="1" />\n' for i in range(lanes))
    mixer = mixer.replace('</mixer>', f'    <!-- Call Screening test: digital silence upstream of AI injection. -->\n    <path name="{PATH}">\n{controls}    </path>\n</mixer>')
    route = ET.fromstring(mixer).findall(f'./path[@name="{PATH}"]')
    assert len(route) == 1 and not route[0].findall('path')
    assert len(route[0].findall('ctl')) == 16 + lanes
    new_voice = [u for d in ET.fromstring(rm).findall('./device_profile/in-device') if d.findtext('id') == 'PAL_DEVICE_IN_HANDSET_MIC' for u in d.findall('usecase') if u.findtext('name') == 'PAL_STREAM_VOICE_CALL'][0]
    assert new_voice.findtext(f'custom-config[@key="{KEY}"]/snd_device_name') == PATH
    return rm, mixer

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--ndk', type=Path)
    spec = importlib.util.spec_from_file_location('v4_builder', HERE.parent/'build_candidate.py')
    old = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(old)
    old.device_arguments(p)
    a = old.resolve_device(p.parse_args())
    compiler = old.ndk_compiler(a.ndk or old.default_ndk())
    assert os.environ.get('SRC_DIR'), 'Source buildenv.sh <target> first'
    out = a.output.resolve()
    assert out.is_relative_to(Path('/tmp')) and not out.exists(), 'Use a new /tmp output directory'
    fw = a.vendor_fw
    stock = {}
    for rel, accepted in PINS.items():
        if not (fw/rel).is_file():
            unsupported(f'{a.device}: missing {fw/rel}')
        stock[rel] = sha(fw/rel)
        if stock[rel] not in accepted:
            unsupported(f'{a.device}: {rel} {stock[rel]}')
    if not (a.system_fw/TINYALSA).is_file() or sha(a.system_fw/TINYALSA) != TINYALSA_SHA:
        unsupported(f'{a.device}: {a.system_fw/TINYALSA}')
    # Also hook stop so a stopped capture is not mistaken for a running one.
    functions = [*old.FUNCTIONS, 'stop']
    old.FUNCTIONS = functions
    hal, edits = old.patch_hal((fw/'vendor/lib64/hw/audio.primary.kalama.so').read_bytes())
    rm, mixer = patched_configs(fw/'vendor')
    module = out/'module'
    libs = module/'candidate/vendor/lib64'
    (libs/'hw').mkdir(parents=True)
    (libs/'hw/audio.primary.kalama.so').write_bytes(hal)
    config = module/'candidate'/CONFIG
    config.mkdir(parents=True)
    (config/'resourcemanager.xml').write_text(rm)
    (config/'mixer_paths.xml').write_text(mixer)
    flags = ['-std=c11','-D_GNU_SOURCE','-Wall','-Wextra','-Werror','-O2','-Wl,-z,relro,-z,now,-z,max-page-size=16384']
    definitions = ['-Dpal_stream_'+s+'=cs__stream_'+s for s in functions]
    subprocess.run([str(compiler),*flags,'-fPIC','-shared','-Wl,-soname,libcs-pal.so',*definitions,
                    str(HERE/'compat_tx.c'),str(HERE/'sources.c'),'-Wl,--no-as-needed',str(fw/'vendor/lib64/libar-pal.so'),
                    '-Wl,--as-needed','-Wl,--allow-shlib-undefined','-ldl','-llog','-o',str(libs/'libcs-pal.so')],check=True)
    subprocess.run([str(compiler),*flags,str(HERE/'probe.c'),str(HERE/'sources.c'),'-ldl','-o',str(module/'source_probe')],check=True)
    (module/'module.prop').write_text('''id=rezoss_callscreen_compat_tx_v6
name=Call Screening TX v6 isolation TEST
version=0.6-isolated-codec
versionCode=6
author=Rezoss
description=Inactive until arm_v6.sh. Dedicated ZERO-source route and kernel readback; audio repair unverified.
''')
    (module/'skip_mount').touch()
    (module/'device').write_text(a.device+'\n')
    (module/'stock.sha256').write_text(''.join(v+'  /'+k+'\n' for k,v in stock.items())+TINYALSA_SHA+'  /system/lib64/libtinyalsa.so\n')
    for file in ('arm_v6.sh','action.sh','customize.sh'):
        shutil.copy2(HERE/file,module/file)
        (module/file).chmod(0o755)
    (module/'source_probe').chmod(0o755)
    payload = sorted(p for p in (module/'candidate').rglob('*') if p.is_file())
    (module/'payload.sha256').write_text(''.join(sha(p)+'  '+str(p.relative_to(module))+'\n' for p in payload))
    for unsafe in ('system','vendor','post-fs-data.sh','service.sh','metamount.sh','boot-completed.sh'):
        assert not (module/unsafe).exists()
    report = {
        'status':'INACTIVE HOST-TESTED CANDIDATE; NOT INSTALLED OR DEVICE-AUDIO-VERIFIED',
        'device':a.device,'source_pins':stock,'dynamic_string_edits':edits,
        'tinyalsa_sha256':TINYALSA_SHA,
        'payload':{str(p.relative_to(module)):sha(p) for p in payload},
        'source':{p.name:sha(p) for p in (HERE/'compat_tx.c',HERE/'sources.c',HERE/'build_v6.py')},
        'isolation':'Screening TX key selects dedicated codec source ZERO path; read 16 kernel enum controls before/after open/start/route and on mute/param',
        'not_a_mute_getter':'No pal_stream_get_mute or pal_get_mic_mute calls; HAL mute requests pass through',
        'remaining':['Actual HAL-domain tinyalsa control access','PAL honors dedicated key and silent four-lane route starts','Voice DSP graph startup','Caller hears AI and ASR works','Caller-side physical isolation test','Direct PAL users outside patched HAL are not covered by capture-admission hooks'],
    }
    (out/'build-report.json').write_text(json.dumps(report,indent=2)+'\n')
    archive = out/'rezoss-callscreen-compat-tx-v6-isolation.zip'
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED) as z:
        for file in sorted(module.rglob('*')):
            if file.is_file():z.write(file,file.relative_to(module))
    print(json.dumps({'module':str(archive),'sha256':sha(archive),'report':str(out/'build-report.json')},indent=2))

if __name__ == '__main__':
    main()
