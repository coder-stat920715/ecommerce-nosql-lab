#!/bin/bash
# Bootstraps replica sets and registers shards with mongos.
wait_for() { until mongosh --host "$1" --port "$2" --quiet --eval "db.adminCommand('ping')" >/dev/null 2>&1; do sleep 2; done; }
wait_for configsvr 27019; wait_for shard1 27018; wait_for shard2 27018
mongosh --host configsvr --port 27019 --quiet --eval 'rs.initiate({_id:"cfgrs",configsvr:true,members:[{_id:0,host:"configsvr:27019"}]})'
mongosh --host shard1 --port 27018 --quiet --eval 'rs.initiate({_id:"shard1rs",members:[{_id:0,host:"shard1:27018"}]})'
mongosh --host shard2 --port 27018 --quiet --eval 'rs.initiate({_id:"shard2rs",members:[{_id:0,host:"shard2:27018"}]})'
sleep 10; wait_for mongos 27017
mongosh --host mongos --port 27017 --quiet --eval 'sh.addShard("shard1rs/shard1:27018"); sh.addShard("shard2rs/shard2:27018"); sh.status()'
