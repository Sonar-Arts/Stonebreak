# Sourced by the Testing scripts: resolves a working Python 3 into the PY array.
# On Windows `python3` is often the Microsoft Store stub (App execution alias): it is on PATH
# but only prints an install hint, so each candidate is actually run, not just looked up.
PY=()
for _py_candidate in python3 python "py -3"; do
  if $_py_candidate -c 'import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1; then
    read -r -a PY <<<"$_py_candidate"
    break
  fi
done
unset _py_candidate
if [[ ${#PY[@]} -eq 0 ]]; then
  echo "No working Python 3 found (tried python3, python, py -3). Install Python 3 and put it on PATH." >&2
  exit 1
fi
