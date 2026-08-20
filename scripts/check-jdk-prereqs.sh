#!/usr/bin/env bash

find_jdk_home() {
  local major="$1"
  local val d drive

  for var in "JAVA_HOME_${major}" "JDK_${major}_HOME" "JAVA${major}_HOME"; do
    val="${!var:-}"
    if [[ -n "$val" ]]; then
      if [[ -x "$val/bin/java" || -x "$val/bin/java.exe" ]]; then
        printf '%s\n' "$val"
        return 0
      fi
    fi
  done

  if [[ -n "${JAVA_HOME:-}" ]] && [[ -x "${JAVA_HOME}/bin/java" || -x "${JAVA_HOME}/bin/java.exe" ]]; then
    if "${JAVA_HOME}/bin/java" -version 2>&1 | head -n 1 | grep -qE "version \"${major}(\\.|\"|$)|version \"1\\.${major}"; then
      printf '%s\n' "$JAVA_HOME"
      return 0
    fi
  fi

  shopt -s nullglob
  local candidates=(
    /usr/lib/jvm/java-"${major}"-openjdk-*
    /usr/lib/jvm/java-"${major}"-*
    /usr/lib/jvm/temurin-"${major}"-*
    /usr/lib/jvm/jdk-"${major}"*
    /usr/lib/jvm/zulu"${major}"*
  )
  for d in "${candidates[@]}"; do
    if [[ -x "$d/bin/java" ]]; then
      printf '%s\n' "$d"
      shopt -u nullglob
      return 0
    fi
  done
  shopt -u nullglob

  if [[ "${ALLOW_WINDOWS_JDK_FROM_WSL:-}" == "1" ]] && grep -qiE 'microsoft|wsl' /proc/version 2>/dev/null; then
    shopt -s nullglob
    for drive in c d e f; do
      candidates=(
        /mnt/"$drive"/Program\ Files/Java/jdk-"${major}"*
        /mnt/"$drive"/Program\ Files/Eclipse\ Adoptium/jdk-"${major}"*
        /mnt/"$drive"/Program\ Files/Microsoft/jdk-"${major}"*
        /mnt/"$drive"/Program\ Files/Amazon\ Corretto/jdk"${major}"*
      )
      for d in "${candidates[@]}"; do
        if [[ -x "$d/bin/java.exe" || -x "$d/bin/java" ]]; then
          printf '%s\n' "$d"
          shopt -u nullglob
          return 0
        fi
      done
    done
    shopt -u nullglob
  fi

  # Native Windows (Git Bash/MSYS/MINGW, not WSL) — drives are already mounted at
  # /c/, /d/, etc., not /mnt/c/. This has no WSL/proc-version gate to check: Git Bash
  # never has /proc/version, so this only ever fires on an actual native-Windows shell.
  case "$(uname -s 2>/dev/null)" in
    MINGW*|MSYS*|CYGWIN*)
      shopt -s nullglob
      for drive in c d e f; do
        candidates=(
          /"$drive"/Program\ Files/Java/jdk-"${major}"*
          /"$drive"/Program\ Files/Eclipse\ Adoptium/jdk-"${major}"*
          /"$drive"/Program\ Files/Microsoft/jdk-"${major}"*
          /"$drive"/Program\ Files/Amazon\ Corretto/jdk"${major}"*
        )
        for d in "${candidates[@]}"; do
          if [[ -x "$d/bin/java.exe" ]]; then
            printf '%s\n' "$d"
            shopt -u nullglob
            return 0
          fi
        done
      done
      shopt -u nullglob
      ;;
  esac

  return 1
}

_jdk_version_line() {
  local home="$1"
  if [[ -x "$home/bin/java" && "$home/bin/java" != *.exe ]]; then

    case "$home" in
      /mnt/[a-zA-Z]/*) echo "(Windows JDK — use from Windows host / cmd.exe)" ; return ;;
    esac
    "$home/bin/java" -version 2>&1 | head -n 1
  elif [[ -x "$home/bin/java.exe" ]]; then
    echo "(Windows JDK at $home)"
  else
    echo "(java binary missing)"
  fi
}

check_jdk_prereqs() {
  JDK17_HOME="$(find_jdk_home 17 || true)"
  JDK21_HOME="$(find_jdk_home 21 || true)"
  export JDK17_HOME JDK21_HOME

  echo "JDK prerequisites (SWRL needs 17; most services need 21):"
  if [[ -n "${JDK17_HOME}" ]]; then
    echo "  OK JDK 17 → $JDK17_HOME"
    echo "     $(_jdk_version_line "$JDK17_HOME")"
  else
    echo "  MISSING JDK 17"
  fi
  if [[ -n "${JDK21_HOME}" ]]; then
    echo "  OK JDK 21 → $JDK21_HOME"
    echo "     $(_jdk_version_line "$JDK21_HOME")"
  else
    echo "  MISSING JDK 21"
  fi

  if [[ -z "${JDK17_HOME}" || -z "${JDK21_HOME}" ]]; then
    echo ""
    echo "  Install both, then re-run. Examples:"
    echo "    # WSL/Ubuntu"
    echo "    sudo apt-get install -y openjdk-17-jdk openjdk-21-jdk"
    echo "    # or set explicit homes:"
    echo "    export JAVA_HOME_17=/usr/lib/jvm/java-17-openjdk-amd64"
    echo "    export JAVA_HOME_21=/usr/lib/jvm/java-21-openjdk-amd64"
    echo "    # Windows: install Temurin/Microsoft JDK 17 + 21, or set JAVA_HOME_17 / JAVA_HOME_21"
    return 1
  fi
  return 0
}

require_jdk_prereqs() {
  if ! check_jdk_prereqs; then
    echo "ERROR: JDK 17 and JDK 21 are both required prerequisites." >&2
    exit 1
  fi
}

require_jdk_prereqs_soft() {
  if ! check_jdk_prereqs; then
    echo "WARNING: Host JDK 17/21 missing. Docker image builds still embed their own JDKs;"
    echo "         desktop / local Maven builds will fail until both are installed."
    return 1
  fi
  return 0
}
