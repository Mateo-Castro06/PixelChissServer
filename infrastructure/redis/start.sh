#!/bin/sh
set -eu

# Default filenames for the Redis volume in PixelChiss Compose.
has_aof=no
if [ -f /data/appendonlydir/appendonly.aof.manifest ] || [ -f /data/appendonly.aof ]; then
    has_aof=yes
fi

mode=${REDIS_APPENDONLY:-auto}
case "$mode" in
    auto)
        if [ "$has_aof" = yes ] || [ ! -f /data/dump.rdb ]; then
            mode=yes
        else
            mode=no
            echo "PixelChiss: preserving existing RDB. Convert on the running server before enabling AOF." >&2
        fi
        ;;
    yes)
        if [ "$has_aof" = no ] && [ -f /data/dump.rdb ]; then
            echo "PixelChiss: refusing to bypass existing RDB; convert on the running server first." >&2
            exit 1
        fi
        ;;
    no)
        if [ "$has_aof" = yes ]; then
            echo "PixelChiss: refusing to bypass existing AOF." >&2
            exit 1
        fi
        ;;
    *) echo "PixelChiss: REDIS_APPENDONLY must be auto, yes or no." >&2; exit 1 ;;
esac

exec /usr/local/bin/docker-entrypoint.sh redis-server \
    --appendonly "$mode" --appendfsync everysec --maxmemory-policy noeviction
