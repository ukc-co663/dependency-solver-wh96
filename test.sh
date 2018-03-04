#!/bin/sh

path="../depsolver/tests/seen-"

for i in $(eval echo {$1..$2}); do
  echo "Testing Seen $i"
  ./solve "$path$i/repository.json" "$path$i/initial.json" "$path$i/constraints.json"
  # echo "$path$i/repository.json" "$path$i/initial.json" "$path$i/constraints.json"
  sleep 1
  echo
done
