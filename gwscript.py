#!/usr/bin/env python3

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path.cwd().resolve()
AVED = ROOT / "v80-aved-platform-srv32i"

AVED_HW = AVED / "hw/amd_v80_gen5x8_25.1"
USER_ACCEL = AVED_HW / "src/rtl/user_accel"
GENERATED = ROOT / "generated/AxiSRV32I"
VENV = ROOT / "chisel-axi-utils/.venv"
STATE_FILE = ROOT / ".build-state.json"

completed = set()


def run(cmd, *, cwd=ROOT, env=None):
    """Run command and fail immediately on non-zero exit status."""
    print(f"\n[{cwd}] $ {' '.join(map(str, cmd))}")
    subprocess.run(
        cmd,
        cwd=cwd,
        env=env,
        check=True,
    )


def capture(cmd, *, cwd=ROOT):
    """Run command, check exit status, and return stdout."""
    result = subprocess.run(
        cmd,
        cwd=cwd,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    return result.stdout.strip()


def venv_env():
    """Return an environment equivalent to activating the project venv."""
    env = os.environ.copy()
    env["VIRTUAL_ENV"] = str(VENV)
    env["PATH"] = f"{VENV / 'bin'}:{env['PATH']}"
    return env


def load_state():
    if not STATE_FILE.exists():
        return {}

    try:
        return json.loads(STATE_FILE.read_text())
    except (json.JSONDecodeError, OSError):
        return {}


def save_stage_success(stage, detail=None):
    state = {
        "last_successful_stage": stage,
        "timestamp": datetime.now().isoformat(timespec="seconds"),
    }

    if detail:
        state["detail"] = str(detail)

    STATE_FILE.write_text(json.dumps(state, indent=2) + "\n")


def show_status():
    state = load_state()

    stage = state.get("last_successful_stage")
    if not stage:
        print("No successful stage recorded.")
        return

    print(f"Last successful stage: {stage}")

    if "timestamp" in state:
        print(f"Time: {state['timestamp']}")

    if "detail" in state:
        print(f"Detailed output: {state['detail']}")


# ----------------------------------------------------------------------
# Stages
# ----------------------------------------------------------------------

def prereq():
    verilator = capture(["verilator", "--version"])
    print(f"Verilator: {verilator}")

    m = re.search(r"(\d+\.\d+)", verilator)
    if not m or m.group(1) != "5.044":
        print("WARNING: Verilator 5.044 is recommended.")

    gcc = capture(["gcc", "-dumpfullversion"])
    print(f"GCC: {gcc}")

    if not gcc.startswith("12."):
        print("WARNING: GCC 12.* is recommended.")


def repo():
    status = capture(["git", "submodule", "status"])

    uninitialized = any(
        line.startswith("-")
        for line in status.splitlines()
        if line.strip()
    )

    if uninitialized:
        run(["git", "submodule", "update", "--init"])
    else:
        print("Git submodules already initialized.")


def bridge():
    run(["make", "-C", "chisel-axi-utils", "setup"])


def rtlgen():
    run(["sbt", "runMain srv32i.AxiSRV32I"])

    if not GENERATED.is_dir():
        raise RuntimeError(
            f"RTL generation succeeded but {GENERATED} does not exist"
        )


def cocotb():
    run(
        ["make", "-C", "tests"],
        env=venv_env(),
    )


def aved():
    if AVED.exists():
        if not (AVED / ".git").is_dir():
            raise RuntimeError(
                f"{AVED} exists but does not appear to be a git repository"
            )
        print(f"AVED repository already exists: {AVED}")
    else:
        run([
            "git",
            "clone",
            "https://github.com/hwspec/v80-aved-platform-.git",
            str(AVED),
        ])

        if not (AVED / ".git").is_dir():
            raise RuntimeError("AVED clone did not complete successfully")

    os.environ["AVED"] = str(AVED)
    print(f"AVED={AVED}")


def useracc():
    USER_ACCEL.mkdir(parents=True, exist_ok=True)

    files = []
    for pattern in ("*.v", "*.sv", "*.json"):
        files.extend(GENERATED.glob(pattern))

    if not files:
        raise RuntimeError(f"No generated RTL/JSON files found in {GENERATED}")

    for src in files:
        dst = USER_ACCEL / src.name
        print(f"copy {src} -> {dst}")
        shutil.copy2(src, dst)


def firmware():
    log_path = ROOT / "aved-compile.log"

    print(f"\n[{AVED_HW}] $ ./build_all.sh")
    print(f"Logging output to {log_path}")

    with log_path.open("w") as logfile:
        proc = subprocess.Popen(
            ["./build_all.sh"],
            cwd=AVED_HW,
            env=os.environ.copy(),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )

        assert proc.stdout is not None

        for line in proc.stdout:
            sys.stdout.write(line)
            logfile.write(line)

        rc = proc.wait()

    if rc != 0:
        raise subprocess.CalledProcessError(rc, ["./build_all.sh"])

    print("Firmware compilation completed successfully.")
    return log_path


def program():
    run(["make", "prog"], cwd=AVED)

    run([
        "sudo",
        "/usr/local/bin/ami_tool",
        "reload",
        "-t", "sbr",
        "-d", "b1:00.0",
    ])


def fpga():
    env = venv_env()
    env["AVED"] = str(AVED)

    old_pythonpath = env.get("PYTHONPATH", "")
    env["PYTHONPATH"] = (
        f"{old_pythonpath}:{AVED}/sw/runtime/src"
        if old_pythonpath
        else f"{AVED}/sw/runtime/src"
    )

    env["LD_LIBRARY_PATH"] = (
        f"{AVED}/sw/runtime/build:"
        f"{AVED}/sw/AMI/api/build/"
    )

    env["PARAMFN"] = str(
        AVED_HW / "src/rtl/user_accel/Recode2_params.json"
    )

    run(
        ["sh", "run_on_fpga.sh", "axisrv32i"],
        cwd=ROOT / "tests",
        env=env,
    )

    output_log = ROOT / "tests/output.log"

    if not output_log.is_file():
        raise RuntimeError(f"Expected output file not found: {output_log}")

    print(f"\nFPGA output: {output_log}")
    print("-" * 72)

    lines = output_log.read_text(errors="replace").splitlines()
    for line in lines[-30:]:
        print(line)

    return output_log


# ----------------------------------------------------------------------
# Dependency graph
# ----------------------------------------------------------------------

STAGES = {
    "prereq":   ([], prereq),
    "repo":     (["prereq"], repo),
    "bridge":   (["repo"], bridge),
    "rtlgen":   (["repo"], rtlgen),
    "cocotb":   (["rtlgen", "bridge"], cocotb),
    "aved":     (["prereq"], aved),
    "useracc":  (["rtlgen", "aved"], useracc),
    "firmware": (["useracc"], firmware),
    "program":  (["firmware"], program),
    "fpga":     (["program"], fpga),
}


def execute_stage(name):
    if name in completed:
        return

    dependencies, func = STAGES[name]

    for dep in dependencies:
        execute_stage(dep)

    print()
    print("=" * 72)
    print(f"STAGE: {name}")
    print("=" * 72)

    detail = func()
    save_stage_success(name, detail)
    completed.add(name)


def main():
    parser = argparse.ArgumentParser()

    parser.add_argument(
        "--status",
        action="store_true",
        help="Show the last successfully completed stage",
    )

    parser.add_argument(
        "stage",
        nargs="?",
        default=None,
        choices=STAGES.keys(),
        help="Target stage; dependencies are run automatically (default: fpga)",
    )

    args = parser.parse_args()

    if args.status:
        show_status()
        return

    stage = args.stage or "fpga"

    print(f"Project root: {ROOT}")

    try:
        execute_stage(stage)
    except subprocess.CalledProcessError as e:
        print(
            f"\nERROR: command failed with exit code {e.returncode}",
            file=sys.stderr,
        )
        sys.exit(e.returncode or 1)
    except Exception as e:
        print(f"\nERROR: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
