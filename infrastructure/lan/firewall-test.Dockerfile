# Test-only image. Never used by the application Compose files.
# Reuses the existing Linux/Python Lobby image; no host firewall or private mounts.
FROM pixelchiss-lobby:latest
RUN apt-get update && apt-get install -y --no-install-recommends iptables nftables
