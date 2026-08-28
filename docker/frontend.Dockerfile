FROM node:22-alpine AS build
WORKDIR /app

# Lockfile first, so a source-only change does not reinstall the dependency tree.
COPY frontend/package.json frontend/pnpm-lock.yaml* ./
RUN corepack enable && pnpm install --frozen-lockfile

COPY frontend/ ./
RUN pnpm run build

FROM nginx:alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
# 127.0.0.1 rather than localhost: inside the container localhost resolves to ::1 first and nginx
# binds IPv4 only, so a localhost check can never connect. The container then never reports
# healthy, which is both misleading and enough to block anything gated on its health.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
    CMD wget --quiet --tries=1 --spider http://127.0.0.1/ || exit 1
