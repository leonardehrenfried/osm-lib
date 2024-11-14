#! /bin/bash

for run in {1..100}; do
  wget localhost:9002/33.654066160697056,-84.759521484375,33.994611584814606,-84.30976867675781.pbf -O output/cancelled.osm.pbf &
  PID=$!
  sleep 1
  kill $PID
done

