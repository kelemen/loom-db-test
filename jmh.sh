#!/bin/bash

set -euo pipefail
shopt -s extglob

script_dir=$(cd "$(dirname "$0")" && pwd)

jmh_benchmark_args=()

while [[ $# > 0 ]] ; do
  case "$1" in
    --@(testedDb|poolSize|connectionAction|dbPoolType|forkType|cpuWork|cpuSleepMs|fullConcurrentTasks)?(=*))
      param_key_name="${1#--}"
      if [[ ${param_key_name} =~ .*=.* ]]; then
        param_value="${param_key_name#*=}"
        param_key_name="${param_key_name%%=*}"
      else
        param_value="$2"
        shift
      fi

      if [[ ${param_key_name} = testedDb ]]; then
        db_names="${db_names+${db_names}.}${param_value}"
      else
        jmh_benchmark_args+=("-Pbenchmark.${param_key_name}=${param_value}")
      fi
      ;;
    *)
     echo 1>&2 "Unexpected parameter: $1"
     exit 1
     ;;
  esac
  shift
done

if [[ ${db_names:-} = "" ]]; then
  echo 1>&2 "Missing required --testedDb parameter"
  exit 2
fi

dest_dir="${script_dir}/jmh-results"

mkdir -p "${dest_dir}"

(
src_results_file="${script_dir}/build/results/jmh/results.txt"

IFS=,

for db_name in $(echo "${db_names}"); do
  rm -f "${dest_file}"
done

for db_name in $(echo "${db_names}"); do
  dest_file="${dest_dir}/${db_name}.results.txt"
  rm -f "${src_results_file}"

  jmh_success=Y
  "${script_dir}/gradlew" "-PtestedDb=${db_name}" "${jmh_benchmark_args[@]}" jmh --rerun || jmh_success=N
  error_code=$?

  if [[ ${jmh_success} = Y && -f "${src_results_file}" ]]; then
    cp "${src_results_file}" "${dest_file}"
    echo "Results for ${db_name} are stored in ${dest_file}."
  else
    echo 1>&2 "Failed for ${db_name}"
    echo "jmh failed (with error ${error_code}) for benchmark parameters:" "${jmh_benchmark_args[@]}" > "${dest_file}"
  fi
done

echo "Completed all benchmarks"

for db_name in $(echo "${db_names}"); do
  echo ""
  echo "Results for ${db_name}"
  echo ""
  cat "${dest_dir}/${db_name}.results.txt"
done
)
