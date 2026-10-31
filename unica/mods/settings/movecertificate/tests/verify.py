#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Host checks; never mounts a store or modifies firmware/target output.

SECILC=/path/to/Android-compatible/secilc python3 .../tests/verify.py
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

source = Path(__file__).resolve().parents[1]
settings = source.parent
repo = settings.parents[2]
temp = Path(tempfile.mkdtemp(prefix='unica-movecert-verify-'))

def run(*args, **kwargs):
    subprocess.run(list(map(str, args)), check=True, **kwargs)

# The explicit import artifact is a public CA certificate, not a private key.
cert = repo / 'out/archived/adguard/adguard_2710_155458.crt'
expected = subprocess.check_output(['openssl', 'x509', '-in', str(cert), '-subject_hash_old', '-noout'], text=True).strip()
classes = temp / 'classes'
classes.mkdir()
run('javac', '-d', classes, source / 'java/io/mesalabs/unica/movecertificate/Certificates.java', source / 'tests/CertificatesTest.java')
# Generate disposable public fixtures; no private key is written to disk.
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from datetime import datetime, timedelta, timezone
key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
now = datetime.now(timezone.utc)
fixtures = []
for name, ca, usage, before, after in [('leaf', False, False, -1, 1), ('badusage', True, False, -1, 1), ('expired', True, True, -3, -1), ('future', True, True, 1, 2), ('intermediate', True, True, -1, 1)]:
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'AdGuard Personal Intermediate' if name == 'intermediate' else name)])
    fixture = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
               .not_valid_before(now + timedelta(days=before)).not_valid_after(now + timedelta(days=after))
               .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True)
               .add_extension(x509.KeyUsage(False, False, False, False, False, usage, usage, False, False), critical=True).sign(key, hashes.SHA256()))
    path = temp / (name + '.pem')
    path.write_bytes(fixture.public_bytes(serialization.Encoding.PEM))
    fixtures.append(path)
run('java', '-cp', classes, 'io.mesalabs.unica.movecertificate.CertificatesTest', cert, expected, *fixtures)
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
android_jar = Path(os.environ.get('ANDROID_JAR', str(sdk / 'platforms/android-37.0/android.jar')))
stubs = temp / 'stubs'
stubs.mkdir()
run('javac', '-source', '8', '-target', '8', '-Xlint:-options', '-cp', android_jar, '-d', stubs,
    *sorted((settings / 'thermal/stubs').rglob('*.java')))
classpath = os.pathsep.join(map(str, (stubs, classes, android_jar)))
run('javac', '-source', '8', '-target', '8', '-Xlint:-options', '-cp', classpath, '-d', classes,
    source / 'java/io/mesalabs/unica/movecertificate/AdcertController.java', source / 'tests/AdcertSelectionTest.java')
run('java', '-cp', classpath, 'io.mesalabs.unica.movecertificate.AdcertSelectionTest', cert, fixtures[-1])
native = temp / 'native'
native.mkdir()
run('/usr/bin/g++', '-std=c++17', '-Wall', '-Wextra', '-Werror', source / 'tests/worker_test.cpp', '-o', temp / 'worker-test')
run(temp / 'worker-test', native)

android = '{http://schemas.android.com/apk/res/android}'
menu = list(ET.parse(settings / 'SecSettings.apk/res/xml/unica_spoof_settings.xml').getroot())
assert menu[-3].get(android + 'key') == 'unica_hma_privacy'
assert menu[-1].get(android + 'key') == 'unica_adcert'
assert menu[-1].tag == 'SecSwitchPreference'
assert menu[-1].get('{http://schemas.android.com/apk/res-auto}controller') == 'io.mesalabs.unica.movecertificate.AdcertController'
patch = settings / 'smali/system/priv-app/SecSettings/SecSettings.apk/0013-Add-Move-Certificate-manager.patch'
patched = temp / 'patched'
patched.mkdir()
run('patch', '--dry-run', '--fuzz=0', '-p1', '-i', patch, cwd=patched, stdout=subprocess.DEVNULL)
run('patch', '--fuzz=0', '-p1', '-i', patch, cwd=patched, stdout=subprocess.DEVNULL)
run('smali', 'assemble', '--api', '35', patched / 'smali_classes4', '-o', temp / 'verified.dex')
print('PASS Settings switch placement and zero-fuzz patch/DEX verification')

work = temp / 'policy'
firmware = Path(os.environ['WORK_DIR']) if os.environ.get('WORK_DIR') else repo / 'out/target' / os.environ['TARGET_CODENAME'] / 'work_dir'
for part in ('system/system', 'vendor', 'system_ext', 'product', 'odm'):
    src = firmware / part / 'etc/selinux'
    if src.is_dir():
        shutil.copytree(src, work / part / 'etc/selinux')
run('python3', source / 'install-policy.py', work)
snapshot = {p.relative_to(work): p.read_bytes() for p in work.rglob('*') if p.is_file()}
run('python3', source / 'install-policy.py', work)
assert snapshot == {p.relative_to(work): p.read_bytes() for p in work.rglob('*') if p.is_file()}
policy_text = (work / 'system/system/etc/selinux/plat_sepolicy.cil').read_text()
# context= labels the superblock first, then checks relabelfrom and
# association of the root inode against that newly labeled superblock.
assert '(allow unica_movecert system_security_cacerts_file (filesystem (mount remount unmount relabelfrom relabelto)))' in policy_text
assert '(allow system_security_cacerts_file system_security_cacerts_file (filesystem (associate)))' in policy_text
compiler = os.environ.get('SECILC')
if not compiler:
    raise SystemExit('Set SECILC to an Android-compatible compiler to complete policy verification')
plat = work / 'system/system/etc/selinux'
version = (work / 'vendor/etc/selinux/plat_sepolicy_vers.txt').read_text().strip()
inputs = [plat / 'plat_sepolicy.cil', plat / 'mapping' / (version + '.cil')]
for part in ('system_ext', 'product'):
    inputs += [work / part / 'etc/selinux' / (part + '_sepolicy.cil'), work / part / 'etc/selinux/mapping' / (version + '.cil')]
inputs += [work / 'vendor/etc/selinux/plat_pub_versioned.cil', work / 'vendor/etc/selinux/vendor_sepolicy.cil']
odm = work / 'odm/etc/selinux/odm_sepolicy.cil'
if odm.exists():
    inputs.append(odm)
# Android init combines shipped policy with neverallow checks disabled.
run(compiler, '-m', '-M', 'true', '-G', '-N', '-c', '30', '-o', temp / 'policy.bin', '-f', temp / 'file_contexts', *inputs)
print('PASS policy installer idempotence and combined runtime policy compilation')
print('Artifacts:', temp)
