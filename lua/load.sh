#!/usr/bin/env bash

source ../.env

redis-cli  -h "$REDIS_HOST" -p "$REDIS_PORT" -x FUNCTION LOAD REPLACE < stream_utils.claim_or_dlq.lua
redis-cli  -h "$REDIS_HOST" -p "$REDIS_PORT" FUNCTION LIST

