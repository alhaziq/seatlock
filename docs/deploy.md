# Deploying Seatlock on one VM

Everything (Postgres, Kafka, Redis, api, worker, Prometheus, Grafana, Caddy) runs from `compose.prod.yaml`.

## VM size

2 vCPU and 4 GB RAM is the minimum we would try, but it is untested: the only passing load run used 4 vCPU / 4 GiB, and a 2 vCPU / 2 GiB run failed its thresholds (see [the results](../load/results/2026-11-local.md)). Memory is the constraint: Kafka is capped at a 512 MB heap, Postgres, two JVMs (api, worker), Prometheus and Grafana each need several hundred MB, and the OS needs headroom. 2 GB will OOM under load.

## DNS

Create an A record for your domain pointing at the VM's public IP, and open ports 80 and 443. Caddy obtains and renews the TLS certificate itself; it needs the record in place first.

## Docker

Install Docker Engine with the compose plugin (see https://docs.docker.com/engine/install/), then log out and back in so your user can run `docker`.

## Configure

From your machine, create the deploy directory (`~/seatlock`) on the VM and copy the template over:

```sh
ssh user@host 'mkdir -p ~/seatlock'
scp .env.example user@host:seatlock/.env
```

Then edit `.env` on the VM, run `chmod 600 .env`, and set every value. `SEATLOCK_AUTH_JWT_SECRET` must be at least 32 bytes (`openssl rand -hex 32` gives 64 characters); the app refuses to start otherwise. Compose refuses to start if any required value is missing. `.env` stays on the VM and is never copied by `deploy.sh`.

Changing `POSTGRES_PASSWORD` after the first start does not change the database user's password, which is stored in the `pgdata` volume. Run `docker compose -f compose.prod.yaml exec postgres psql -U seatlock -c "ALTER USER seatlock PASSWORD '<new>'"` first, then update `.env`.

Only Caddy publishes ports. Only the **api** is public: Caddy routes `/` to `api:8080` and `/grafana/*` to Grafana. The worker also serves HTTP on 8080 but is not routed, and the management port (8081) of both is reachable only inside the compose network, where Prometheus scrapes it. Grafana allows anonymous read-only access, so anonymous viewers can run any PromQL query against the app's metrics; the admin login uses `GF_ADMIN_PASSWORD`.

## First deploy

From your machine, with SSH access to the VM and `.env` already created there:

```sh
deploy/deploy.sh user@host
```

This copies `compose.prod.yaml` and `deploy/` to `~/seatlock` and runs `docker compose pull && up -d`. The image comes from `SEATLOCK_IMAGE` (published by CI on pushes to `main`); if the ghcr package is private, run `docker login ghcr.io` on the VM first. Check `docker compose -f compose.prod.yaml ps`: all services should be `healthy`. Then open `https://<domain>/grafana/`.

## Updating

Set `SEATLOCK_IMAGE` in `.env` to the new tag (or keep `:latest`) and run `deploy/deploy.sh user@host` again. Flyway migrations run on api start.

## Backups

```sh
docker compose -f compose.prod.yaml exec -T postgres pg_dump -U seatlock seatlock | gzip > seatlock-$(date +%F).sql.gz
```

Kafka, Prometheus and Grafana data are not backed up; Postgres is the source of truth.

## Cost

Monthly VM cost: _to be filled in by the author_.
