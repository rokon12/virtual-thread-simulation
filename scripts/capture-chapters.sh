#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
output_dir="${1:-$project_dir/target/chapter-snapshots}"
mkdir -p "$output_dir"
cd "$project_dir"

for chapter in 1 2 3 4 5 6; do
  mvn -q javafx:run -Djavafx.args="--seed=42 --snapshot=$output_dir/chapter-$chapter.ppm --snapshot-chapter=$chapter --snapshot-at=5.5"
done

echo "Chapter snapshots: $output_dir"
