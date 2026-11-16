#!/usr/bin/env python3
"""Run the standard x86-64 NDK on ARM64 Linux without a privileged binfmt install."""
import os
from pathlib import Path
import shlex
import subprocess
import sys


def main():
    toolchain = Path(os.environ['ANDROID_NDK_HOME']) / 'toolchains/llvm/prebuilt/linux-x86_64'
    emulator = [os.environ['QEMU_X86_64'], '-L', os.environ['QEMU_LD_PREFIX']]
    if os.environ.get('QEMU_EXTRA_LIBRARY_PATH'):
        emulator += ['-E', 'LD_LIBRARY_PATH=' + os.environ['QEMU_EXTRA_LIBRARY_PATH']]
    compiler = toolchain / 'bin/clang'
    resources = list((toolchain / 'lib/clang').iterdir())
    if len(resources) != 1:
        raise ValueError('expected one NDK Clang resource directory')
    arguments = ['--target=aarch64-linux-android33', '--sysroot=' + str(toolchain / 'sysroot'),
                 '-resource-dir=' + str(resources[0]), *sys.argv[1:]]
    plan = subprocess.run([*emulator, str(compiler), '-###', *arguments], text=True, capture_output=True)
    if plan.returncode:
        sys.stderr.write(plan.stderr)
        return plan.returncode
    commands = [shlex.split(line) for line in plan.stderr.splitlines() if line.lstrip().startswith('"')]
    if not commands:
        return subprocess.run([*emulator, str(compiler), *arguments]).returncode
    for command in commands:
        executable = Path(command[0]).resolve()
        if toolchain.resolve() not in executable.parents:
            raise ValueError('compiler planned an executable outside its NDK toolchain')
        result = subprocess.run([*emulator, *command])
        if result.returncode:
            return result.returncode
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
