podman build -f Dockerfile.api -t chatserver/api-server:latest .
podman build -f Dockerfile.worker -t chatserver/worker:latest .
podman-compose up -d
podman-compose down