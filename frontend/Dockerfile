# =========================================================
# APS Cloud frontend — Vite + React build, served by Caddy.
# Caddy also reverse-proxies /auth, /api, /admin to the backend.
# =========================================================

# ---------- build stage ----------
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json* ./
# package-lock.json is optional — fall back to npm install if missing
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi

COPY index.html vite.config.js ./
COPY src ./src
RUN npm run build

# ---------- runtime stage ----------
FROM caddy:2.8-alpine AS runtime
COPY --from=build /app/dist /usr/share/caddy
COPY Caddyfile /etc/caddy/Caddyfile

EXPOSE 80 443
# /healthz on :80 is a plain 200 from Caddy (Caddyfile bypasses the
# HTTP→HTTPS redirect for that path), so the healthcheck doesn't need
# TLS or external DNS.
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
  CMD wget -qO- http://127.0.0.1/healthz >/dev/null 2>&1 || exit 1
